# AGENTS.md

## Source of truth

This repository builds one Android product: **Al-Shorti / الشرطي**.

Read these first:
- `police-app/build.gradle.kts`
- `police-app/src/main/kotlin/com/malik/alshurti/`
- `version.properties`
- `docs/alshurti.md`
- `docs/release.md`

## Permanent product identity

- App name: `الشرطي`
- Android namespace: `com.malik.alshurti`
- Android application ID: `com.malik.alshurti`

**Never change the application ID.** It is intentionally different from VibeApp (`com.vibe.app`) so both apps can coexist. Keeping this ID stable is also required for in-place updates.

For every distributable version, increment `VERSION_CODE` in `version.properties`. A higher `VERSION_NAME` alone is not sufficient.

Release builds must use the same signing key for the lifetime of the app. Never commit a private production keystore to this public repository.

## Product contract

- Android, Arabic-first, minSdk 29 / targetSdk 36.
- Opens directly into a police-dog voice-call scene.
- Primary priorities: cinematic character realism, natural Arabic speech, and low time-to-first-audio.
- Three-dot menu switches ONLINE / OFFLINE.
- OFFLINE must never silently fall back to network speech recognition.
- STT, brain, TTS, lip-sync, and 3D rendering remain independent replaceable engines.
- The character is fictional and must not claim a real police unit was contacted or dispatched.
- Real danger routes the child to a trusted adult / real emergency help and never solicits sensitive child data.

## Architecture

Current buildable baseline:

`SpeechRecognizer -> PoliceBrain -> Android TTS -> Arabic visemes -> animated character`

Target neural path:

`streaming/on-device STT -> Qwen-class conversational brain -> Arabic neural TTS -> timed visemes -> rigged GLB/Filament`

Do not claim photorealistic 3D, neural Arabic TTS, or measured latency until those components are actually present and validated on a device.

## Verification

```bash
./gradlew :police-app:assembleDebug
./gradlew :police-app:testDebugUnitTest
./gradlew :police-app:lintDebug
```
