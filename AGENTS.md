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
- Primary priorities: cinematic character realism, a native Saudi human-sounding male voice, and low conversational delay.
- Production TTS must not fall back to Android TextToSpeech, Supertonic, or another robotic/general-Arabic voice.
- Current production TTS is ElevenLabs `eleven_multilingual_v2` with a native Saudi male voice.
- The production voice path is ONLINE. Do not expose an offline voice option until an offline engine can genuinely satisfy the same Saudi/natural quality bar.
- STT, brain, TTS, lip-sync, and 3D rendering remain independent replaceable engines.
- The character is fictional and must not claim a real police unit was contacted or dispatched.
- Real danger routes the child to a trusted adult / real emergency help and never solicits sensitive child data.

## Voice configuration

- `ELEVENLABS_API_KEY` must come from the build environment or Gradle property; never commit it.
- `ALSHORTI_ELEVENLABS_VOICE_ID` may override the default Saudi voice id.
- Any replacement voice must be native Saudi Arabic, male, conversational, and verified by listening on real app dialogue before release.
- Never accept “supports Arabic” as sufficient evidence of Saudi pronunciation quality.
- Do not claim a voice is natural/production-ready until it is listened to on an Android device using representative Saudi dialogue.

## Visual contract

The production character is `RealPoliceDogStage` with a rigged PBR GLB rendered through SceneView/Filament. `PoliceDogStage` is a development fallback only.

The final office should feel alive rather than like a static wallpaper: subtle staff movement, door activity, phone ringing, environmental sounds, and scenario-driven interruptions. Background dialogue must use a distinct voice and must not talk over the child without an intentional scene event.

## Verification

```bash
./gradlew :police-app:assembleDebug
./gradlew :police-app:testDebugUnitTest
./gradlew :police-app:lintDebug
```

A build passing is necessary but not sufficient for release. Voice and GLB realism require device validation.
