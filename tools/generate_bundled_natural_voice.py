#!/usr/bin/env python3
import base64
import itertools
import json
import math
import os
import pathlib
import re
import struct
import time
import urllib.error
import urllib.request
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = ROOT / "police-app/src/main/assets/natural_voice_catalog.json"
RAW_DIR = ROOT / "police-app/src/main/res/raw"
API_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
API_REVISION = "2026-05-20"
MODELS = ["gemini-2.5-flash-preview-tts", "gemini-3.1-flash-tts-preview"]
GROUP_SIZE = 3
WINDOW_MS = 20
MIN_BOUNDARY_SILENCE_MS = 420
MIN_UTTERANCE_SECONDS = 0.70


def make_prompt(texts):
    transcript = "\n[long pause]\n".join(texts)
    return (
        "Generate one speech audio clip only. Do not speak or paraphrase these directions.\n"
        "Voice direction: Native Saudi man from Riyadh. Natural conversational Najdi/Saudi accent. "
        "Mature calm presence, warm with children, confident but never theatrical. Speak like a real "
        "Saudi person nearby, not a broadcaster. Preserve colloquial Saudi wording. Medium-low pitch, "
        "relaxed pace, subtle breathing, no announcer cadence.\n"
        "The transcript contains multiple utterances separated by [long pause]. Speak every utterance "
        "exactly and in order. Interpret [long pause] as at least 1.2 seconds of silence; do not speak "
        "the words 'long pause' and do not add labels or transitions.\n"
        "[TRANSCRIPT]\n" + transcript
    )


def parse_retry_seconds(body: str):
    seconds = re.search(r"retry in\s+([0-9.]+)s", body, re.IGNORECASE)
    if seconds:
        return float(seconds.group(1))
    milliseconds = re.search(r"retry in\s+([0-9.]+)ms", body, re.IGNORECASE)
    if milliseconds:
        return float(milliseconds.group(1)) / 1000.0
    return None


def request_once(api_key: str, voice: str, texts, model: str):
    payload = json.dumps({
        "model": model,
        "input": make_prompt(texts),
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
    with urllib.request.urlopen(req, timeout=120) as response:
        return extract_audio(json.load(response))


def request_group(api_key: str, voice: str, texts):
    last_error = None
    for model in MODELS:
        for attempt in range(5):
            try:
                audio = request_once(api_key, voice, texts, model)
                print(f"  using {model}", flush=True)
                return audio
            except urllib.error.HTTPError as exc:
                last_error = exc
                try:
                    body = exc.read().decode("utf-8", errors="replace")[:1200]
                except Exception:
                    body = ""
                print(f"  {model}: HTTP {exc.code} attempt {attempt + 1}/5 {body}", flush=True)
                if exc.code != 429:
                    if exc.code in (500, 502, 503, 504) and attempt < 4:
                        time.sleep(min(2 ** attempt, 10))
                        continue
                    break
                retry = parse_retry_seconds(body)
                wait = min(max((retry or (2 ** attempt)) + 1.0, 1.5), 65.0)
                print(f"  quota wait {wait:.1f}s", flush=True)
                time.sleep(wait)
            except Exception as exc:
                last_error = exc
                print(f"  {model}: {type(exc).__name__}: {exc}", flush=True)
                if attempt < 4:
                    time.sleep(min(2 ** attempt, 10))
                else:
                    break
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


def decode_pcm(raw: bytes, mime: str, reported_rate: int):
    if raw[:4] == b"RIFF" or mime.startswith("audio/wav"):
        temp = RAW_DIR / ".voice-group-temp.wav"
        temp.write_bytes(raw)
        try:
            with wave.open(str(temp), "rb") as wav:
                if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
                    raise RuntimeError("Gemini grouped WAV must be mono PCM16")
                rate = wav.getframerate()
                pcm = wav.readframes(wav.getnframes())
        finally:
            temp.unlink(missing_ok=True)
        return pcm, rate
    usable = raw[: len(raw) - (len(raw) % 2)]
    return usable, reported_rate or 24000


def window_rms(samples, start, end):
    if end <= start:
        return 0.0
    total = 0
    for sample in samples[start:end]:
        total += sample * sample
    return math.sqrt(total / (end - start))


def trimmed_bounds(samples, start_sample, end_sample, window, threshold, rate):
    start = start_sample
    end = end_sample
    pad = int(rate * 0.08)
    while start + window < end and window_rms(samples, start, start + window) <= threshold:
        start += window
    while end - window > start and window_rms(samples, end - window, end) <= threshold:
        end -= window
    return max(start_sample, start - pad), min(end_sample, end + pad)


def split_on_long_silence(pcm: bytes, rate: int, count: int):
    if count == 1:
        return [pcm]
    sample_count = len(pcm) // 2
    samples = struct.unpack("<" + "h" * sample_count, pcm[:sample_count * 2])
    window = max(1, rate * WINDOW_MS // 1000)
    rms_values = [
        window_rms(samples, start, min(start + window, sample_count))
        for start in range(0, sample_count, window)
    ]
    if not rms_values:
        raise RuntimeError("empty grouped PCM")
    peak = max(rms_values)
    silence_threshold = max(90.0, peak * 0.025)
    min_windows = max(1, MIN_BOUNDARY_SILENCE_MS // WINDOW_MS)

    runs = []
    run_start = None
    for index, rms in enumerate(rms_values + [peak + 1]):
        silent = index < len(rms_values) and rms <= silence_threshold
        if silent and run_start is None:
            run_start = index
        elif not silent and run_start is not None:
            length = index - run_start
            if length >= min_windows:
                mid_sample = ((run_start + index) * window) // 2
                if int(rate * MIN_UTTERANCE_SECONDS) < mid_sample < sample_count - int(rate * MIN_UTTERANCE_SECONDS):
                    runs.append((length, mid_sample, run_start, index))
            run_start = None

    required = count - 1
    if len(runs) < required:
        longest = sorted((length * WINDOW_MS for length, *_ in runs), reverse=True)
        raise RuntimeError(
            f"Gemini did not create {required} usable long pauses; found {len(runs)} ({longest})"
        )

    print(
        "  silence candidates:",
        [
            {"ms": length * WINDOW_MS, "at_s": round(mid / rate, 2)}
            for length, mid, *_ in sorted(runs, key=lambda item: item[1])
        ],
        flush=True,
    )

    min_segment_samples = int(rate * MIN_UTTERANCE_SECONDS)
    best = None
    best_score = None
    for combo in itertools.combinations(runs, required):
        ordered = sorted(combo, key=lambda item: item[1])
        boundaries = [0] + [item[1] for item in ordered] + [sample_count]
        raw_lengths = [b - a for a, b in zip(boundaries, boundaries[1:])]
        if any(length < min_segment_samples for length in raw_lengths):
            continue

        trimmed = [
            trimmed_bounds(samples, a, b, window, silence_threshold, rate)
            for a, b in zip(boundaries, boundaries[1:])
        ]
        durations = [(end - start) / rate for start, end in trimmed]
        if any(duration < MIN_UTTERANCE_SECONDS for duration in durations):
            continue

        # Real [long pause] boundaries should have the most silence. A small spacing term discourages
        # selecting multiple pauses inside one long sentence when several candidates are similar.
        silence_score = sum(item[0] for item in ordered)
        smallest_duration = min(durations)
        score = silence_score * 1000.0 + smallest_duration
        if best_score is None or score > best_score:
            best_score = score
            best = (ordered, trimmed, durations)

    if best is None:
        raise RuntimeError(
            "No silence-boundary combination can produce all utterances at least "
            f"{MIN_UTTERANCE_SECONDS:.2f}s long"
        )

    chosen, trimmed, durations = best
    print(
        "  chosen boundaries:",
        [round(item[1] / rate, 2) for item in chosen],
        "durations:",
        [round(value, 2) for value in durations],
        flush=True,
    )

    segments = []
    for start, end in trimmed:
        segment_samples = samples[start:end]
        rms = window_rms(segment_samples, 0, len(segment_samples))
        if rms <= 150:
            raise RuntimeError(f"split utterance is effectively silent (RMS={rms:.1f})")
        segments.append(struct.pack("<" + "h" * len(segment_samples), *segment_samples))
    return segments


def write_wav(path: pathlib.Path, pcm: bytes, rate: int):
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(pcm)
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
            raise RuntimeError(f"{path.name}: invalid WAV")
        if wav.getframerate() < 16000 or wav.getnframes() < 1000:
            raise RuntimeError(f"{path.name}: audio too short/low-rate")


def main():
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("GEMINI_API_KEY is required to build audible APK")
    voice = os.environ.get("ALSHORTI_GEMINI_POLICE_VOICE", "").strip() or "Gacrux"
    entries = json.loads(CATALOG.read_text(encoding="utf-8"))
    if len(entries) != 15:
        raise SystemExit(f"expected exactly 15 catalog entries, got {len(entries)}")

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    for old in RAW_DIR.glob("voice_*.wav"):
        old.unlink()

    groups = [entries[i:i + GROUP_SIZE] for i in range(0, len(entries), GROUP_SIZE)]
    for group_index, group in enumerate(groups, 1):
        print(f"[{group_index}/{len(groups)}] generating {len(group)} utterances in one TTS request", flush=True)
        raw, mime, reported_rate = request_group(api_key, voice, [item["text"] for item in group])
        pcm, rate = decode_pcm(raw, mime, reported_rate)
        segments = split_on_long_silence(pcm, rate, len(group))
        if len(segments) != len(group):
            raise RuntimeError("group split count mismatch")
        for item, segment in zip(group, segments):
            target = RAW_DIR / f"voice_{item['id']}.wav"
            write_wav(target, segment, rate)
            if target.stat().st_size < 3000:
                raise RuntimeError(f"{target.name}: file unexpectedly small")
            print(f"  wrote {target.name}", flush=True)
        time.sleep(0.5)

    generated = sorted(p.name for p in RAW_DIR.glob("voice_*.wav"))
    expected = sorted(f"voice_{item['id']}.wav" for item in entries)
    if generated != expected:
        raise RuntimeError(f"generated catalog mismatch: {generated} != {expected}")
    print(f"Generated {len(generated)} bundled natural voice files in {len(groups)} requests.")


if __name__ == "__main__":
    main()
