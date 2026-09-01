#!/usr/bin/env python3
"""Smoke the same Gemini Live WebSocket protocol used by police-live-v2."""

import asyncio
import base64
import json
import os
import sys

import websockets

API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
VOICE = os.environ.get("ALSHORTI_GEMINI_POLICE_VOICE", "").strip() or "Gacrux"
MODEL = "gemini-3.1-flash-live-preview"
ENDPOINT = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
)


async def main() -> int:
    if not API_KEY:
        print("GEMINI_API_KEY missing", file=sys.stderr)
        return 2

    url = f"{ENDPOINT}?key={API_KEY}"
    async with websockets.connect(url, max_size=8 * 1024 * 1024, open_timeout=20) as ws:
        setup = {
            "setup": {
                "model": f"models/{MODEL}",
                "generationConfig": {
                    "responseModalities": ["AUDIO"],
                    "speechConfig": {
                        "voiceConfig": {
                            "prebuiltVoiceConfig": {"voiceName": VOICE}
                        }
                    },
                },
                "systemInstruction": {
                    "parts": [{"text": "تحدث بالعربية السعودية وباختصار."}]
                },
                "inputAudioTranscription": {"languageCodes": ["ar-SA"]},
                "outputAudioTranscription": {},
                "realtimeInputConfig": {
                    "automaticActivityDetection": {
                        "disabled": False,
                        "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
                        "endOfSpeechSensitivity": "END_SENSITIVITY_HIGH",
                        "prefixPaddingMs": 80,
                        "silenceDurationMs": 420,
                    },
                    "activityHandling": "START_OF_ACTIVITY_INTERRUPTS",
                    "turnCoverage": "TURN_INCLUDES_ONLY_ACTIVITY",
                },
            }
        }
        await ws.send(json.dumps(setup, ensure_ascii=False))

        setup_ok = False
        for _ in range(20):
            message = json.loads(await asyncio.wait_for(ws.recv(), 5))
            if "setupComplete" in message:
                setup_ok = True
                break
        if not setup_ok:
            raise RuntimeError("Live setupComplete not received")
        print("LIVE_SETUP=PASS")

        turn = {
            "clientContent": {
                "turns": [
                    {
                        "role": "user",
                        "parts": [
                            {
                                "text": "قل فقط: هلا يا بطل، أنا سامعك."
                            }
                        ],
                    }
                ],
                "turnComplete": True,
            }
        }
        await ws.send(json.dumps(turn, ensure_ascii=False))

        audio_bytes = 0
        transcript = ""
        for _ in range(80):
            message = json.loads(await asyncio.wait_for(ws.recv(), 5))
            content = message.get("serverContent") or {}
            trans = content.get("outputTranscription") or {}
            if trans.get("text"):
                transcript += str(trans["text"])
            model_turn = content.get("modelTurn") or {}
            for part in model_turn.get("parts") or []:
                inline = part.get("inlineData") or {}
                mime = str(inline.get("mimeType") or inline.get("mime_type") or "")
                data = inline.get("data")
                if data and mime.startswith("audio/pcm"):
                    audio_bytes += len(base64.b64decode(data))
            if content.get("turnComplete"):
                break

        if audio_bytes < 5000:
            raise RuntimeError(f"Live returned too little audio: {audio_bytes}")
        if not transcript.strip():
            raise RuntimeError("Live returned no output transcription")

        print(f"LIVE_AUDIO=PASS bytes={audio_bytes}")
        print(f"LIVE_TRANSCRIPT=PASS chars={len(transcript.strip())}")
        print("LIVE_V2_STACK=PASS")
        return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
