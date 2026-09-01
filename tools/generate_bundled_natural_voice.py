#!/usr/bin/env python3
import base64
import json
import os
import pathlib
import time
import urllib.error
import urllib.request
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = ROOT / "police-app/src/main/assets/natural_voice_catalog.json"
RAW_DIR = ROOT / "police-app/src/main/res/raw"
API_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
API_REVISION = "2026-05-20"
MODELS = [
    "gemini-3.1-flash-tts-preview",
    "gemini-2.5-pro-preview-tts",
    "gemini-2.5-flash-preview-tts",
]
TRANSIENT_CODES = {429, 500, 502, 503, 504}
preferred_model_index = 0


def make_prompt(text: str) -> str:
    return (
        "Generate speech audio only. Do not speak or paraphrase these directions.\n"
        "Voice direction: Native Saudi man from Riyadh. Natural conversational Najdi/Saudi accent. "
        "Mature calm presence, warm with children, confident but never theatrical. Speak like a real "
        "Saudi person nearby, not a broadcaster. Preserve colloquial Saudi wording. Medium-low pitch, "
        "relaxed pace, short natural pauses, subtle breathing, no announcer cadence.\n"
        "Speak exactly the transcript after [TRANSCRIPT].\n[TRANSCRIPT]\n" + text
    )


def request_once(api_key: str, voice: str, text: str, model: str):
    payload = json.dumps({
        "model": model,
        "input": make_prompt(text),
        "response_format": {"type": "audio"},
        "generation_config": {"speech_config": [{"voice": voice}]},
    }, ensure_ascii=False).encode("utf-8")

    req = urllib.request.Request(
        API_URL,
        data=payload,
        method="POST",
        headers={
            "x-goog-api-key": api_key,
            "Content-Type": "application/json",
            "Api-Revision": API_REVISION,
        },
    )
    with urllib.request.urlopen(req, timeout=75) as response:
        body = json.load(response)
    return extract_audio(body)


def request_audio(api_key: str, voice: str, text: str):
    global preferred_model_index
    order = list(range(preferred_model_index, len(MODELS))) + list(range(0, preferred_model_index))
    last_error = None

    # Try each official TTS model promptly. If one works, keep it preferred for the rest of the
    # catalog so a quota-limited model is not hammered again for every sentence.
    for model_index in order:
        model = MODELS[model_index]
        for attempt in range(2):
            try:
                audio = request_once(api_key, voice, text, model)
                preferred_model_index = model_index
                print(f"  using {model}", flush=True)
                return audio
            except urllib.error.HTTPError as exc:
                last_error = exc
                body = ""
                try:
                    body = exc.read().decode("utf-8", errors="replace")[:500]
                except Exception:
                    pass
                print(f"  {model}: HTTP {exc.code} attempt {attempt + 1}/2 {body}", flush=True)
                if exc.code not in TRANSIENT_CODES:
                    break
                if attempt == 0:
                    time.sleep(1.5)
            except Exception as exc:
                last_error = exc
                print(f"  {model}: {type(exc).__name__}: {exc}", flush=True)
                if attempt == 0:
                    time.sleep(1.5)

    # One bounded cooldown, then retry the preferred candidate once. This handles short rolling
    # quota windows without turning CI into an unbounded wait.
    time.sleep(8.0)
    model = MODELS[preferred_model_index]
    try:
        audio = request_once(api_key, voice, text, model)
        print(f"  using {model} after cooldown", flush=True)
        return audio
    except Exception as exc:
        last_error = exc

    raise last_error if last_error is not None else RuntimeError("All Gemini TTS models failed")


def extract_audio(body):
    def walk(node):
        if isinstance(node, dict):
            data = node.get("data")
            mime = (node.get("mime_type") or node.get("mimeType") or "").lower()
            if data and (node.get("type") == "audio" or mime.startswith("audio/")):
                raw = base64.b64decode(data)
                rate = int(node.get("sample_rate") or node.get("sampleRate") or 24000)
                return raw, mime, rate
            for value in node.values():
                found = walk(value)
                if found is not None:
                    return found
        elif isinstance(node, list):
            for value in node:
                found = walk(value)
                if found is not None:
                    return found
        return None

    audio = walk(body)
    if audio is None:
        raise RuntimeError("Gemini response contained no audio")
    return audio


def write_wav(path: pathlib.Path, raw: bytes, mime: str, rate: int):
    if raw[:4] == b"RIFF" or mime.startswith("audio/wav"):
        path.write_bytes(raw)
    else:
        usable = raw[: len(raw) - (len(raw) % 2)]
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(rate)
            wav.writeframes(usable)

    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1:
            raise RuntimeError(f"{path.name}: expected mono")
        if wav.getsampwidth() != 2:
            raise RuntimeError(f"{path.name}: expected PCM16")
        if wav.getframerate() < 16000:
            raise RuntimeError(f"{path.name}: sample rate too low")
        if wav.getnframes() < 1000:
            raise RuntimeError(f"{path.name}: audio too short")


def main():
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("GEMINI_API_KEY is required to build audible APK")
    voice = os.environ.get("ALSHORTI_GEMINI_POLICE_VOICE", "").strip() or "Gacrux"
    entries = json.loads(CATALOG.read_text(encoding="utf-8"))
    if not entries:
        raise SystemExit("voice catalog is empty")

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    for old in RAW_DIR.glob("voice_*.wav"):
        old.unlink()

    for index, item in enumerate(entries, 1):
        resource_id = item["id"]
        text = item["text"]
        target = RAW_DIR / f"voice_{resource_id}.wav"
        print(f"[{index}/{len(entries)}] generating {target.name}", flush=True)
        raw, mime, rate = request_audio(api_key, voice, text)
        write_wav(target, raw, mime, rate)
        if target.stat().st_size < 3000:
            raise RuntimeError(f"{target.name}: file unexpectedly small")
        time.sleep(0.35)

    generated = sorted(p.name for p in RAW_DIR.glob("voice_*.wav"))
    expected = sorted(f"voice_{item['id']}.wav" for item in entries)
    if generated != expected:
        raise RuntimeError(f"generated catalog mismatch: {generated} != {expected}")
    print(f"Generated {len(generated)} bundled natural voice files.")


if __name__ == "__main__":
    main()
