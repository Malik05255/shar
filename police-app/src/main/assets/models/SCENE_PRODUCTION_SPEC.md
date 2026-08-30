# Al-Shorti — Cinematic Office 3D Production Contract

The reference is a **feature-film stylized-realistic 3D police dog in a living police office**. It must not look like a flat mascot, mobile-game NPC, photographed real dog, or a frozen render.

## Required GLB files

All assets MUST use the same coordinate system, metric scale, authored world placement and camera composition.

- `office.glb` — complete room, hero desk, chairs, phone, shelves, lamps, wall decor, background workstations and practical lights.
- `police_dog.glb` — hero character only; full body + facial rig.
- `officer_a.glb` — background staff character A.
- `officer_b.glb` — background staff character B.
- `door.glb` — office door and handle with its pivot at the real hinge.

Do not bake all performers into one GLB. They must remain independently animated.

## Hero dog art direction

- German Shepherd / Belgian Malinois inspired anatomy.
- Feature-film stylization with believable canine proportions; not chibi.
- Layered fur direction and breakup, especially muzzle, cheeks, ears, brows, neck and forearms.
- PBR nose, wet-line around eyes, subtle roughness variation, convincing teeth/gums/tongue.
- Police uniform with stitched fabric, seams, folds, badge, utility details and believable cloth weight.
- Seated naturally behind the desk with forearms/hands able to rest, gesture, point and move.
- Eye contact aimed at the child/camera by default.
- No real police agency logo is required; use a fictional police identity.

## Hero rig

Required animation clips (exact names):

- `Idle` — breathing, micro head drift, ear micro-motion, eye saccades and irregular blinking.
- `Listen` — attentive posture, occasional head tilt and tiny ear reactions.
- `Think` — brief look-away/desk glance then back toward child.
- `Smile` — believable canine smile, cheeks/eyes involved, not just mouth corners.
- `Serious` — calmer face and posture, never aggressive or threatening.
- `LookDoor`
- `LookOfficerA`
- `LookOfficerB`
- `TalkRest`
- `TalkOpen`
- `TalkWide`
- `TalkRound`
- `TalkClosed`

The Talk clips must include jaw, lips/muzzle, cheeks, tongue visibility when appropriate, neck micro-motion and subtle body gesture. A jaw-only flap is rejected.

Recommended additional blendshapes/morph targets if supported by the authored pipeline:

- blink_L / blink_R
- brow_inner / brow_outer
- squint_L / squint_R
- smile
- jaw_open
- mouth_close
- mouth_wide
- mouth_round
- tongue_up
- cheek_raise

## Background actors

Exact animation clips for `officer_a.glb` and `officer_b.glb`:

- `Idle`
- `WalkLeft`
- `WalkRight`
- `DeskWork`
- `TurnDoor`
- `Enter`
- `Talk`
- `Exit`

Background actors normally do not speak. Their movement should be slow and asynchronous so the room feels occupied without stealing focus. Only a directed door scenario activates a speaking voice.

## Door

Exact clips:

- `Closed`
- `Opening`
- `Open`
- `Closing`

Handle rotation and latch movement should be visible. Opening/closing timing should match the synchronized Foley cues in `OfficeSoundscapeEngine`.

## Office lighting / camera

- Camera at seated child eye level, slightly below hero dog's eye line.
- Medium close-up: hero head/torso dominate frame while desk and background remain readable.
- Warm key light on hero, cooler practical/background separation.
- Soft rim on ears/shoulders to separate fur from background.
- Avoid crushed blacks; fur silhouette must remain readable on a phone screen.
- Background should be lower contrast and slightly softer than hero.
- Mild depth-of-field look may be baked into art/material strategy, but the hero face must remain crisp.
- Target portrait phone composition first; crop-safe for 19.5:9 to 20:9 displays.

## Performance budget

Target modern Android flagship/mid-high devices:

- Hero dog: ~80k–160k visible triangles, LOD-ready.
- Each background actor: ~35k–70k visible triangles.
- Office + props: ~100k–220k triangles after instancing/optimization.
- Prefer 2K hero textures and 1K–2K background textures; use KTX2/Basis where possible.
- Minimize transparent fur cards. Prefer groom baked into cards/normal/roughness strategy appropriate for Filament mobile rendering.
- Keep draw calls controlled; atlas small props where appropriate.
- 30 FPS is the minimum acceptable fallback; target 60 FPS on capable phones.

## Scene choreography implemented in app

Normal room life:
- background officer walks / works at desk / changes idle poses silently;
- low-level Foley can include typing, paper, chair and distant radio cue;
- phone ring only when the child microphone is not actively listening.

Door interruption scenario:
1. knock;
2. hero looks at door;
3. handle sound + handle motion;
4. door opening sound + animation;
5. officer enters with spatial footsteps;
6. hero looks at that officer;
7. officer says one short Arabic line using a different Chatterbox voice profile;
8. officer exits with footsteps;
9. door closes with matching Foley;
10. hero returns eye contact to child and the call resumes.

The scenario must never interrupt the child while the microphone is actively listening.

## Audio asset override filenames

Real studio/CC0 Foley can replace generated fallbacks by adding these WAV files under `assets/sfx/`:

- `door_handle.wav`
- `door_open.wav`
- `door_close.wav`
- `knock.wav`
- `phone_ring.wav`
- `footsteps.wav`
- `keyboard.wav`
- `paper.wav`
- `chair.wav`
- `radio_beep.wav`

`OfficeSoundscapeEngine` automatically uses these assets when present and keeps its synchronized fallback otherwise.
