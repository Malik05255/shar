# Al-Shorti Cinematic Runtime 3D Content Pack

This pack replaces full-scene MP4 playback with a persistent real-time office world. The existing cinematic reference is the minimum visual benchmark; package size or render convenience must never justify a visible quality reduction.

## Non-negotiable visual benchmark

- Preserve the exact police-dog identity, facial proportions, fur pattern, navy uniform, badge placement and overall silhouette from the approved master reference.
- PBR materials must preserve physically plausible roughness, normals, glass, metal and fabric response under the authored office lighting.
- Hero dog and foreground desk must remain cinematic at phone viewing distance. Do not substitute low-poly, sprite, billboard or flat-card representations.
- Background staff must use natural human body proportions, asynchronous motion and non-synchronized idle cycles.
- All actors are authored in the same coordinate system. Runtime code does not `scaleToUnits` individual actors because that would destroy spatial relationships.
- Full-scene MP4 is migration fallback only. New scenarios are composed from actors + animation clips.

## Coordinate / render contract

- Format: glTF 2.0 binary (`.glb`).
- Units: meters.
- Up axis: +Y.
- Forward: -Z.
- Shared origin: center of the office floor plan.
- Camera and office shell are authored against the approved 16:9 cinematic composition.
- Animation frame rate: authored at 30 or 60 fps; runtime samples continuously by time.
- Skinning: normalized weights, max 4 influences per vertex unless a higher count is required for facial deformation.
- Textures: preserve source fidelity. External delivery means texture resolution is not reduced to save APK size.
- Hero materials: base color + normal + roughness + metallic where physically applicable; fur may use cards/shells or another mobile-safe technique only if the visible result matches the benchmark.
- Lighting: physically based, stable exposure, no automatic visual downgrade based on network conditions.

## Required persistent actors

| Actor ID | File | Role |
| --- | --- | --- |
| `POLICE_DOG` | `police_dog.glb` | Hero character, fully rigged |
| `OFFICE_SHELL` | `office_shell.glb` | Room, walls, windows, fixed lighting geometry |
| `DESK` | `desk.glb` | Foreground police desk |
| `DOOR` | `door.glb` | Animated office door |
| `PHONE` | `phone.glb` | Desk phone |
| `FILE` | `file.glb` | Reusable file / papers prop |
| `MONITOR` | `monitor.glb` | Police monitor |
| `KEYBOARD` | `keyboard.glb` | Keyboard |
| `CHAIR` | `chair.glb` | Foreground chair |
| `PRINTER` | `printer.glb` | Background printer |
| `COFFEE_CUP` | `coffee_cup.glb` | Reusable desk prop |
| `STAFF_MALE_01` | `staff_male_01.glb` | Background officer |
| `STAFF_MALE_02` | `staff_male_02.glb` | Background officer |
| `STAFF_FEMALE_01` | `staff_female_01.glb` | Background officer |

`VISITOR_01` is optional and can be streamed only when a scenario needs it.

## Police dog animation names

The hero GLB must expose these exact clip names:

`IdleWork`, `Breathing`, `Blink`, `EyeSaccade`, `LookAtDesk`, `LookAtMonitor`, `LookAtCamera`, `LookAtDoor`, `LookAtStaff`, `ReachFile`, `ReviewFile`, `TurnPage`, `WriteNote`, `SetFileDown`, `UsePhone`, `Listen`, `Talk`, `StandUp`, `SitDown`, `Walk`, `LeanBack`.

### Hero motion rules

- Silence: never default to `LookAtCamera`; attention remains on desk, monitor, staff or room events.
- User voice activity: eye gaze reacts first, then head, then torso. Do not snap the whole body in one frame.
- Breathing, blink, gaze, facial motion and hands should be authored so they can be layered or blended without full-body pops.
- Talking must support jaw / muzzle deformation suitable for runtime lip-sync; no baked full-scene video mouth movement.
- Idle loops must have clean cycle boundaries but runtime scenario timing must not expose obvious repeated full-body cycles.

## Staff animation names

Each staff character must expose:

`IdleDesk`, `Breathing`, `Blink`, `Type`, `Read`, `Write`, `TalkToStaff`, `ListenToStaff`, `GestureSmall`, `HeadNod`, `Walk`, `WalkCarryFile`, `CarryFile`, `StandUp`, `SitDown`, `UsePhone`, `Drink`, `OpenDoor`, `CloseDoor`.

Staff motions are deliberately asynchronous. No two background actors should share identical idle phase, start time and playback speed.

## Prop animation names

- Door: `OpenDoor`, `CloseDoor`, `Idle`
- Phone: `Ring`, `Idle`
- File: `Idle`, `MoveToDesk`, `MoveToHand`
- Chair: `Idle`, `Shift`, `Turn`
- Printer: `Idle`, `Print`
- Coffee cup: `Idle`, `MoveToHand`, `MoveToDesk`

## Audio contract

There is no continuous synthetic ambience oscillator. Background sound exists only when something in the world physically causes it: footsteps, paper, keyboard, chair, door, phone, printer, cup or distant staff speech. Speech is spatially associated with its actor/zone and ducks while the user is speaking.

## Delivery contract

- The pack is external to the APK and controlled by `runtime3d/manifest.json`.
- Every file has exact `bytes` and SHA-256.
- A new pack is staged completely and promoted atomically only after all required actors validate.
- If one actor is unchanged between pack versions, the app reuses the previous validated bytes and does not redownload it.
- Existing validated pack stays active during download and on any network/validation failure.
- `minimumAppVersion` prevents a content pack from being loaded by an incompatible runtime.

## Acceptance gate

A pack must not be enabled until all of the following pass on a physical Android device:

1. 30-minute silent-observer run with no full-scene freeze or MP4 repetition.
2. Hero never looks at the observer before real voice activity.
3. Background actors continue plausible independent work while hero listens/talks.
4. No obvious synchronized loops across staff.
5. No visible animation snap when plans change.
6. No missing texture/material flashes.
7. Stable frame pacing on the target phone.
8. Visual comparison against the approved cinematic MP4 reference shows no accepted downgrade in hero identity, lighting, materials or framing.
