# الشرطي — Voice Character Architecture

## Product goal

The app opens directly into a single cinematic police-dog character. The child talks naturally in Arabic; the character listens, thinks, replies, and visibly reacts. The three-dot menu switches between online and offline speech preference without exposing developer settings to the child.

## Current vertical slice

```text
Microphone
  -> Android SpeechRecognizer (ar-SA, partial results)
  -> PoliceBrain contract + child-safety guard
  -> Android TextToSpeech (best Arabic voice for selected mode)
  -> PoliceDogStage expression / mouth animation
  -> automatic return to listening
```

The code deliberately separates state, brain, speech I/O, and rendering so each engine can be replaced without changing the call screen.

## Target neural stack

The production-quality target keeps the same interfaces and replaces the baseline engines with:

```text
Offline
  STT: whisper.cpp or sherpa-onnx
  Brain: Qwen GGUF through llama.cpp / another proven Android runtime
  TTS: on-device neural Arabic TTS through sherpa-onnx

Online / self-hosted
  STT: streaming Whisper/Qwen ASR
  Brain: Qwen service with the exact PoliceCharacterContract
  TTS: Chatterbox/Fish-style neural Arabic voice when a self-hosted GPU is available
```

No paid API is required by the architecture. Online mode is intended for a user-owned/self-hosted endpoint when the neural backend is added.

## Character rendering contract

`PoliceDogStage` is currently a lightweight cinematic animated fallback so the application is buildable before a licensed 3D character is committed. The final asset should be a rigged high-fidelity dog in police uniform seated behind a desk.

Recommended asset contract for the future Filament renderer:

- Format: GLB / glTF 2.0, physically based materials.
- Mobile LODs: high, medium, low.
- Textures: 2K default; optional 4K only for high-end devices.
- Fur: baked/cards or mobile-friendly groom representation; avoid desktop strand hair.
- Skeleton: head, neck, jaw, ears, eyelids, brows, shoulders, forelegs.
- Facial morph targets: jawOpen, mouthWide, mouthNarrow, smile, blinkL, blinkR, browUp, browDown.
- Animation clips: Idle, Listen, Think, Talk, Smile, Laugh, Serious, Concerned.
- Keep the camera fixed enough that facial animation remains readable on a phone.
- Desk and room may be separate meshes to allow quality scaling.

## Latency budget

For the experience to feel like a phone call, optimize for time-to-first-audio rather than full-response completion:

- End-of-speech detection: ~0.35–0.70 s after natural pause.
- Brain first tokens: target <0.5 s on a capable local runtime or LAN server.
- TTS first audio: target <0.4 s with streaming neural synthesis.
- Overall target: first reply audio around 1–1.6 s after the child finishes speaking on a suitable device/backend.

The current Android fallback is device/engine dependent and therefore cannot guarantee that target; the neural adapters are where deterministic latency work belongs.

## Safety and scope

`PoliceCharacterContract` is a code-level contract, not just UI copy. It keeps the character child-appropriate, blocks requests for sensitive personal information, avoids threats or fake dispatch claims, and routes real danger to a trusted adult / real emergency services.

The character must remain clearly an in-app fictional police character. It must never claim that a real police unit has been contacted or dispatched.
