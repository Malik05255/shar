#!/usr/bin/env python3
"""Live CI smoke test for Al-Shorti's actual online conversation stack.

The test deliberately exercises the same public APIs used by the Android app:
1. Gemini text conversation model.
2. Gemini TTS with non-empty audio output.
3. Gemini Transcribe using one bundled Arabic WAV.

This is not a substitute for physical-device audio testing, but it prevents an APK from being
published when the configured key/model/schema cannot complete the server half of the experience.
"""

from __future__ import annotations

import base64
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request
from typing import Any

API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
POLICE_VOICE = os.environ.get("ALSHORTI_GEMINI_POLICE_VOICE", "").strip() or "Gacrux"
BASE_MODELS = "https://generativelanguage.googleapis.com/v1beta/models"
INTERACTIONS = "https://generativelanguage.googleapis.com/v1beta/interactions"
TEXT_MODEL = "gemini-3.5-flash-lite"
TTS_MODEL = "gemini-3.1-flash-tts-preview"
TRANSCRIBE_MODEL = "gemini-3.5-transcribe"


def request_json(url: str, payload: dict[str, Any], attempts: int = 3) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last_error: Exception | None = None
    for attempt in range(attempts):
        req = urllib.request.Request(
            url,
            data=body,
            method="POST",
            headers={
                "x-goog-api-key": API_KEY,
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "AlShorti-CI-Smoke/0.7",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=50) as response:
                raw = response.read().decode("utf-8")
                return json.loads(raw)
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:700]
            last_error = RuntimeError(f"HTTP {exc.code}: {detail}")
            if exc.code not in (429, 500, 502, 503, 504) or attempt == attempts - 1:
                raise last_error
        except Exception as exc:  # network reset/DNS/transient timeout
            last_error = exc
            if attempt == attempts - 1:
                raise
        time.sleep(1.5 * (attempt + 1))
    raise last_error or RuntimeError("request failed")


def text_from_generate_content(root: dict[str, Any]) -> str:
    candidates = root.get("candidates") or []
    if not candidates:
        return ""
    parts = ((candidates[0].get("content") or {}).get("parts") or [])
    return "".join(str(part.get("text") or "") for part in parts if isinstance(part, dict)).strip()


def walk(node: Any):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from walk(value)


def find_audio(root: dict[str, Any]) -> bytes | None:
    for node in walk(root):
        data = node.get("data")
        mime = str(node.get("mime_type") or node.get("mimeType") or "")
        kind = str(node.get("type") or "")
        if isinstance(data, str) and data and (kind == "audio" or mime.startswith("audio/")):
            try:
                decoded = base64.b64decode(data)
            except Exception:
                continue
            if len(decoded) >= 3000:
                return decoded
    return None


def find_interaction_text(root: dict[str, Any]) -> str:
    direct = str(root.get("output_text") or root.get("outputText") or "").strip()
    if direct:
        return direct
    collected: list[str] = []
    for step in root.get("steps") or []:
        if not isinstance(step, dict) or step.get("type") != "model_output":
            continue
        for block in step.get("content") or []:
            if isinstance(block, dict) and block.get("type") == "text":
                value = str(block.get("text") or "").strip()
                if value:
                    collected.append(value)
    return " ".join(collected).strip()


def smoke_conversation() -> None:
    payload = {
        "contents": [
            {
                "role": "user",
                "parts": [
                    {
                        "text": (
                            "أخرج JSON فقط: {\"text\":\"...\",\"mood\":\"CALM\"}. "
                            "أنت شخصية كلب شرطة خيالية لطيفة لطفل. رد باختصار على: أنا ما أبي أنام"
                        )
                    }
                ],
            }
        ],
        "generationConfig": {"maxOutputTokens": 160, "responseMimeType": "application/json"},
    }
    root = request_json(f"{BASE_MODELS}/{TEXT_MODEL}:generateContent", payload)
    text = text_from_generate_content(root)
    if not text:
        raise RuntimeError("conversation model returned no text")
    parsed = json.loads(text)
    if not str(parsed.get("text") or "").strip():
        raise RuntimeError("conversation JSON has no spoken text")
    print("CONVERSATION_SMOKE=PASS")


def smoke_tts() -> None:
    payload = {
        "model": TTS_MODEL,
        "input": "Generate speech audio only. Speak naturally in Saudi Arabic. [TRANSCRIPT] هلا يا بطل، أنا سامعك.",
        "response_format": {"type": "audio"},
        "generation_config": {"speech_config": [{"voice": POLICE_VOICE}]},
    }
    root = request_json(INTERACTIONS, payload)
    audio = find_audio(root)
    if not audio:
        raise RuntimeError("TTS returned no usable audio payload")
    print(f"TTS_SMOKE=PASS bytes={len(audio)} voice={POLICE_VOICE}")


def smoke_transcribe() -> None:
    raw_dir = pathlib.Path("police-app/src/main/res/raw")
    wavs = sorted(raw_dir.glob("voice_*.wav"))
    if not wavs:
        raise RuntimeError("no bundled WAV available for transcription smoke test")
    wav = wavs[0].read_bytes()
    payload = {
        "model": TRANSCRIBE_MODEL,
        "input": [
            {
                "type": "audio",
                "data": base64.b64encode(wav).decode("ascii"),
                "mime_type": "audio/wav",
            }
        ],
        "generation_config": {
            "transcription_config": {"language_codes": ["ar-SA"], "mode": "smart"}
        },
    }
    root = request_json(INTERACTIONS, payload)
    text = find_interaction_text(root)
    if not text:
        raise RuntimeError("transcription returned empty text")
    print(f"TRANSCRIBE_SMOKE=PASS text_chars={len(text)}")


def main() -> int:
    if not API_KEY:
        print("GEMINI_API_KEY missing", file=sys.stderr)
        return 2
    smoke_conversation()
    smoke_tts()
    smoke_transcribe()
    print("ONLINE_STACK_SMOKE=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
