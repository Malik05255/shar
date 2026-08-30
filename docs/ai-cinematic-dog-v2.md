# AI Cinematic Dog V2

This branch upgrades Al-Shorti from a static photoreal fallback to a deterministic cinematic performance system built around one master K9 identity.

## Visual direction
- Match the user's selected K9 precinct reference: friendly anthropomorphic police dog centered at the desk, active animal-officer precinct behind glass, premium cinematic 3D look.
- Never use flat cartoon/anime/game-avatar rendering as the production target.
- Main dog remains visually consistent across every state.
- Background staff are secondary, quiet, and never distract from the child.

## Performance layers
The dog performance is decomposed into independent channels so a future rigged GLB or AI-video asset can map to the same state contract:
- breath: subtle chest/shoulder cycle
- blink: irregular blink timing, never a fixed loop
- gaze: camera / phone / door / staff
- head yaw and pitch: follow gaze after eyes
- torso yaw: follows head with delay and lower amplitude
- ear left/right: small independent reaction to sound cues
- jaw openness: speech energy
- muzzle tension: calm / smile / serious
- micro posture: tiny non-repeating shift

## Attention timing
For external events the order is eyes -> head -> torso:
1. eyes shift immediately
2. head follows ~160 ms later
3. torso follows ~320 ms later
4. return happens in the same hierarchy after the cue ends

## Conversation rules
- listening always suppresses office Foley
- child speech cancels staff events immediately
- speaking drives jaw/muzzle energy
- phone/door/staff events only run between completed police turns
- background staff do not talk over the child

## Asset slots
The runtime looks for these optional AI motion clips under `res/raw` and falls back safely when absent:
- `dog_idle_loop.mp4`
- `dog_listening_loop.mp4`
- `dog_speaking_loop.mp4`
- `dog_phone_react.mp4`
- `dog_door_react.mp4`

When proper clips are bundled, they must use the same master character, camera, uniform, office, lighting and framing so transitions are not visible.
