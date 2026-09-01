#!/usr/bin/env python3
import asyncio
import json
import math
import os
import pathlib
import shutil
import struct
import subprocess
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = ROOT / "police-app/src/main/assets/natural_voice_catalog.json"
RAW_DIR = ROOT / "police-app/src/main/res/raw"
DEFAULT_VOICE = "ar-SA-HamedNeural"
DEFAULT_RATE = "-4%"
DEFAULT_PITCH = "-2Hz"
SAMPLE_RATE = 24000
MIN_DURATION_SECONDS = 0.70
MIN_RMS = 150.0


def pcm_rms(frames: bytes) -> float:
    sample_count = len(frames) // 2
    if sample_count <= 0:
        return 0.0
    samples = struct.unpack("<" + "h" * sample_count, frames[: sample_count * 2])
    return math.sqrt(sum(sample * sample for sample in samples) / sample_count)


def validate_wav(path: pathlib.Path) -> tuple[float, float]:
    if not path.exists() or path.stat().st_size <= 3000:
        raise RuntimeError(f"{path.name}: missing or unexpectedly small")

    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        width = wav.getsampwidth()
        rate = wav.getframerate()
        frame_count = wav.getnframes()
        frames = wav.readframes(frame_count)

    if channels != 1:
        raise RuntimeError(f"{path.name}: expected mono, got {channels}")
    if width != 2:
        raise RuntimeError(f"{path.name}: expected PCM16, got sample width {width}")
    if rate != SAMPLE_RATE:
        raise RuntimeError(f"{path.name}: expected {SAMPLE_RATE} Hz, got {rate}")

    duration = frame_count / float(rate)
    if duration < MIN_DURATION_SECONDS:
        raise RuntimeError(f"{path.name}: too short ({duration:.2f}s)")

    rms = pcm_rms(frames)
    if rms <= MIN_RMS:
        raise RuntimeError(f"{path.name}: effectively silent (RMS={rms:.1f})")
    return duration, rms


def require_runtime_tools() -> None:
    if shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg is required for deterministic PCM WAV conversion")
    try:
        import edge_tts  # noqa: F401
    except ImportError as exc:
        raise SystemExit("edge-tts is required; install pinned edge-tts before generation") from exc


def convert_to_pcm16(source: pathlib.Path, target: pathlib.Path) -> None:
    completed = subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-ac",
            "1",
            "-ar",
            str(SAMPLE_RATE),
            "-c:a",
            "pcm_s16le",
            str(target),
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"ffmpeg failed for {source.name}: {completed.stderr.strip()[-1200:]}"
        )


async def synthesize_one(item: dict, voice: str, rate: str, pitch: str) -> None:
    import edge_tts

    resource_id = item["id"]
    text = item["text"].strip()
    target = RAW_DIR / f"voice_{resource_id}.wav"
    temp_media = RAW_DIR / f".voice_{resource_id}.mp3"

    last_error = None
    for attempt in range(1, 5):
        temp_media.unlink(missing_ok=True)
        target.unlink(missing_ok=True)
        try:
            communicator = edge_tts.Communicate(
                text=text,
                voice=voice,
                rate=rate,
                pitch=pitch,
                volume="+0%",
                receive_timeout=45,
            )
            await communicator.save(str(temp_media))
            if not temp_media.exists() or temp_media.stat().st_size <= 2500:
                raise RuntimeError("neural service returned an empty/small media file")

            convert_to_pcm16(temp_media, target)
            duration, rms = validate_wav(target)
            print(
                f"  wrote {target.name}: {duration:.2f}s, RMS={rms:.1f}, voice={voice}",
                flush=True,
            )
            temp_media.unlink(missing_ok=True)
            return
        except Exception as exc:
            last_error = exc
            temp_media.unlink(missing_ok=True)
            target.unlink(missing_ok=True)
            print(
                f"  {resource_id}: attempt {attempt}/4 failed: {type(exc).__name__}: {exc}",
                flush=True,
            )
            if attempt < 4:
                await asyncio.sleep(min(2 ** attempt, 12))

    raise RuntimeError(f"failed to synthesize {resource_id} after retries") from last_error


async def main_async() -> None:
    require_runtime_tools()
    entries = json.loads(CATALOG.read_text(encoding="utf-8"))
    if len(entries) != 15:
        raise SystemExit(f"expected exactly 15 catalog entries, got {len(entries)}")

    voice = os.environ.get("ALSHORTI_EDGE_POLICE_VOICE", "").strip() or DEFAULT_VOICE
    rate = os.environ.get("ALSHORTI_EDGE_POLICE_RATE", "").strip() or DEFAULT_RATE
    pitch = os.environ.get("ALSHORTI_EDGE_POLICE_PITCH", "").strip() or DEFAULT_PITCH

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    for old in RAW_DIR.glob("voice_*.wav"):
        old.unlink()
    for old in RAW_DIR.glob(".voice_*.mp3"):
        old.unlink()

    print(
        f"Generating {len(entries)} bundled Saudi neural utterances with {voice} "
        f"(rate={rate}, pitch={pitch}).",
        flush=True,
    )

    # Intentionally sequential. The output is generated only at build time, and serial requests avoid
    # burst throttling while keeping every utterance independently replaceable and auditable.
    for index, item in enumerate(entries, 1):
        print(f"[{index}/{len(entries)}] {item['id']}", flush=True)
        await synthesize_one(item, voice, rate, pitch)
        await asyncio.sleep(0.25)

    generated = sorted(path.name for path in RAW_DIR.glob("voice_*.wav"))
    expected = sorted(f"voice_{item['id']}.wav" for item in entries)
    if generated != expected:
        raise RuntimeError(f"generated catalog mismatch: {generated} != {expected}")

    for name in expected:
        validate_wav(RAW_DIR / name)
    print(f"Generated and validated all {len(expected)} bundled Saudi neural WAV files.")


def main() -> None:
    asyncio.run(main_async())


if __name__ == "__main__":
    main()
