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
MODEL = "gemini-3.1-flash-tts-preview"


def request_audio(api_key: str, voice: str, text: str):
    prompt = (
        "Generate speech audio only. Do not speak or paraphrase these directions.\n"
        "Voice direction: Native Saudi man from Riyadh. Natural conversational Najdi/Saudi accent. "
        "Mature calm presence, warm with children, confident but never theatrical. Speak like a real "
        "Saudi person nearby, not a broadcaster. Preserve colloquial Saudi wording. Medium-low pitch, "
        "relaxed pace, short natural pauses, subtle breathing, no announcer cadence.\n"
        "Speak exactly the transcript after [TRANSCRIPT].\n[TRANSCRIPT]\n" + text
    )
    payload = json.dumps({
        "model": MODEL,
        "input": prompt,
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

    last_error = None
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=75) as response:
                body = json.load(response)
            return extract_audio(body)
        except urllib.error.HTTPError as exc:
            last_error = exc
            if exc.code not in (429, 500, 502, 503, 504) or attempt == 5:
                raise
            time.sleep(min(2 ** attempt, 20))
        except Exception as exc:
            last_error = exc
            if attempt == 5:
                raise
            time.sleep(min(2 ** attempt, 20))
    raise last_error


def extract_audio(body):
    candidates = []
    for step in body.get("steps", []):
        if step.get("type") == "model_output":
            candidates.extend(step.get("content", []))
    for key in ("output_audio", "outputAudio"):
        node = body.get(key)
        if isinstance(node, dict):
            candidates.append(node)
    for node in candidates:
        if not isinstance(node, dict) or not node.get("data"):
            continue
        mime = (node.get("mime_type") or node.get("mimeType") or "").lower()
        if node.get("type") == "audio" or mime.startswith("audio/"):
            raw = base64.b64decode(node["data"])
            rate = int(node.get("sample_rate") or node.get("sampleRate") or 24000)
            return raw, mime, rate
    raise RuntimeError("Gemini response contained no audio")


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
