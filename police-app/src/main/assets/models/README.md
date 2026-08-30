# Police dog production asset contract

The production character must be committed here as:

`police_dog.glb`

The app deliberately does **not** label the lightweight Compose fallback as the final character. `RealPoliceDogStage` switches automatically to this GLB when it exists.

## Visual target

- Realistic Belgian Malinois or German Shepherd proportions; not a mascot, chibi, cartoon, or low-poly animal.
- Natural PBR fur using mobile-friendly baked textures / cards rather than desktop strand grooming.
- Real textile police uniform/vest with believable seams, folds, badge/patch materials and hardware.
- Character authored seated behind a realistic police desk, facing the child/camera.
- Eyes, wet nose, teeth, tongue and inner mouth must be modeled/textured for close phone framing.
- Neutral cinematic office lighting must survive Filament mobile rendering.
- Default mobile textures: 2K PBR. Use 4K only as an optional high-end LOD, never the mandatory baseline.

## Rig

At minimum:

- root / pelvis / spine / neck / head
- jaw
- left/right ears
- left/right eyelids
- left/right eyes
- shoulders / forelegs / paws

The face must be deformable. A static mesh with only whole-head rotation is not acceptable.

## Required animation clips

The Android renderer switches these clips reactively:

- `Idle`
- `Listen`
- `Think`
- `Smile`
- `Serious`
- `TalkOpen`
- `TalkWide`
- `TalkRound`
- `TalkClosed`
- `TalkRest`

The five `Talk*` clips are short looping facial/body poses driven by the Arabic speech cursor. Keep the root transform fixed so switching visemes does not make the character jump.

Recommended optional clips:

- `Laugh`
- `Concerned`
- `Blink`
- `Nod`

## Lip-sync meaning

- `TalkClosed`: ب / م / ف closure family
- `TalkRound`: و / ؤ rounded lips
- `TalkWide`: ي / س / ش / ث / ز / ج wider mouth
- `TalkOpen`: ا / أ / إ / آ / ع / ه / ح / خ / ق / ك open family
- `TalkRest`: neutral transition

For higher fidelity later, these poses can be converted to named morph targets without changing the speech engine.

## Mobile budget

Target the default LOD at roughly:

- <= 120k visible triangles for character + desk foreground
- <= 2K textures by default
- <= 80 MB GLB preferred
- physically based materials; avoid excessive transparent fur layers
- one main character skeleton
- no per-frame CPU mesh deformation outside normal skinning/morphing

## Licensing

Only commit an asset whose license clearly allows redistribution inside this app. Keep source/license attribution in this directory. Do not copy random web previews or copyrighted film/game characters.
