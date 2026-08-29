# AGENTS.md

## Source of truth

This repository now builds **Al-Shorti / الشرطي**, not VibeApp. The production module is `police-app`.

Use these files first:
- `police-app/build.gradle.kts`
- `police-app/src/main/kotlin/com/malik/alshurti/`
- `docs/alshurti.md`

Legacy VibeApp directories (`app`, `build-engine`, `build-tools`, `shadow-runtime`) are retained only as reference and are intentionally excluded from `settings.gradle.kts`. Do not wire them back into the build unless the user explicitly requests it.

## Product contract

- Android app, Arabic-first, minSdk 29 / targetSdk 36.
- App opens directly to one police-dog call scene.
- The character is fictional and must never claim a real police unit was contacted/dispatched.
- Main priorities: cinematic character realism, natural Arabic speech, and low turn latency.
- The three-dot menu switches between ONLINE and OFFLINE modes.
- OFFLINE mode must never silently use the network.
- Keep STT, conversational brain, TTS, and character rendering replaceable as independent engines.
- Real danger must route the child to a trusted adult / real emergency help; never request sensitive child data.

## Current implementation

The current vertical slice uses Android SpeechRecognizer + Android TTS and a lightweight local guarded response engine. These are baseline adapters only. The target neural architecture is documented in `docs/alshurti.md`.

The current Canvas dog is a buildable animated fallback. The final visual target is a licensed/generated rigged GLB rendered with a mobile-appropriate 3D pipeline, with facial morph targets and animation states.

## Verification

Run:

```bash
./gradlew :police-app:assembleDebug
./gradlew :police-app:testDebugUnitTest
./gradlew :police-app:lintDebug
```

Do not claim photorealistic 3D, Qwen integration, neural Arabic TTS, or verified latency until those components are actually present and measured on-device.
