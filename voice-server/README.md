# Al-Shorti Real Voice Backend

This backend is the high-quality `ONLINE` mode for the Android app.

Pipeline:

`Android Whisper STT -> Qwen3 8B -> Chatterbox Multilingual V3 Arabic -> WAV -> Android`

The phone no longer loads the Qwen conversational model or a robotic TTS engine in Online mode.

## Why a backend

Human-like Arabic TTS plus a materially stronger conversational model cannot meet the project's latency/quality target on an ordinary Android phone while also remaining unlimited and free of metered APIs. The backend runs on hardware you control, so there is no per-minute/per-character API billing.

## Recommended hardware

For the intended near-real-time experience:

- NVIDIA GPU strongly recommended.
- 12 GB+ VRAM is a practical target for Qwen3 8B quantized plus Chatterbox, depending on exact runtime/model residency.
- 16 GB+ system RAM.
- Phone and computer/server on the same LAN/Wi-Fi.
- Ethernet for the server is preferred.

CPU-only mode is supported for development but should not be considered the final low-latency experience.

## Install Ollama

Install Ollama on the computer, then run once:

```bash
ollama pull qwen3:8b
```

The backend uses Ollama at `http://127.0.0.1:11434` by default.

## Python setup

Python 3.11 is recommended.

```bash
cd voice-server
python -m venv .venv
```

Windows:

```powershell
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python server.py
```

Linux/macOS:

```bash
source .venv/bin/activate
pip install -r requirements.txt
python server.py
```

The server listens on port `8787` and advertises `_alshorti._tcp.local` through mDNS. The Android app discovers it automatically.

## Voice references

For best Saudi Arabic identity, add short, clean, consented Arabic reference recordings:

```text
voice-server/voices/police_ar.wav
voice-server/voices/officer_a_ar.wav
voice-server/voices/officer_b_ar.wav
```

Recommendations:

- WAV, clean mono/stereo speech, no music/reverb.
- Same Arabic/Saudi language/accent as the generated speech.
- Roughly 8–20 seconds of natural speaking is a useful reference range.
- Use consenting speakers; do not clone an identifiable real officer/person without permission.
- Main dog and the two office staff should use noticeably different speakers.

If a reference file is missing, Chatterbox uses its default speaker behavior, but production quality should be judged with the chosen Saudi references installed.

## Test backend

```bash
curl http://127.0.0.1:8787/v1/health
```

Expected response includes:

```json
{
  "status": "ok",
  "tts": "chatterbox-multilingual-v3",
  "language": "ar",
  "llm": "qwen3:8b"
}
```

Test conversation:

```bash
curl -X POST http://127.0.0.1:8787/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"text":"بابا أخذ كرتي، وش أسوي؟","history":[]}'
```

Test voice:

```bash
curl -X POST http://127.0.0.1:8787/v1/tts \
  -H "Content-Type: application/json" \
  -d '{"text":"هلا، معك الشرطي. وش عندك؟","voice":"police"}' \
  --output test.wav
```

## Docker

A Dockerfile and compose file are included, but for reliable LAN mDNS discovery the simplest first production test is running `python server.py` directly on the host OS. Container networking/mDNS behavior varies across Docker Desktop/Linux configurations.

## Environment variables

- `PORT` default `8787`
- `OLLAMA_BASE_URL` default `http://127.0.0.1:11434`
- `OLLAMA_MODEL` default `qwen3:8b`
- `CHATTERBOX_DEVICE` default `cuda` when CUDA is available, otherwise `cpu`
- `CHATTERBOX_EXAGGERATION` default `0.55`
- `CHATTERBOX_CFG_WEIGHT` default `0.35`
- `VOICE_DIR` default `/app/voices` in Docker; when running directly, set it to the local `voices` path if required

## Latency strategy

- Keep both Qwen and Chatterbox warm while the call is active.
- Android keeps Whisper STT local so raw child audio does not need to be uploaded for transcription.
- Replies are intentionally short to reduce both LLM and TTS latency.
- Main voice and scene-interruption voices are separate profiles.
- Office Foley is generated/played on the phone and is not sent over the network.
