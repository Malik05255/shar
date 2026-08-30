# Police dog production asset contract

The production character must be committed here as:

`police_dog.glb`

The app deliberately does **not** show the old lightweight Compose/cartoon dog as the final character. `RealPoliceDogStage` switches to this GLB only when the correct production asset exists.

## Visual target — locked

The target is **cinematic realistic 3D animation**, not a photographed real dog and not a flat/cartoon mascot.

Think of a high-end animated feature-film character: believable anatomy, fur, materials and lighting, but with expressive animated eyes, brows, cheeks, ears and mouth.

- Belgian Malinois or German Shepherd inspired dog.
- Realistic canine proportions and anatomy.
- High-quality stylized-realistic 3D face: expressive and friendly, but never chibi, mascot, low-poly or children's-TV cartoon.
- Dense-looking fur rendered with mobile-friendly PBR textures/cards.
- Natural wet nose, eye reflections, teeth, tongue and inner mouth suitable for close-up phone framing.
- Wearing a believable dark police uniform/vest with textile weave, seams, folds, badge/patches and hardware.
- **Seated naturally behind a police desk**, upper body visible, facing the child/camera as if in a live call.
- Realistic police-office background with desk surface, soft practical lights and cinematic depth of field feel.
- Film-style key/fill/rim lighting; warm face/eye readability with darker office depth.
- Character should feel like a frame from a premium 3D animated movie, not a mobile-game avatar.

### Framing

Default portrait composition:

- camera at eye level or slightly below eye level
- dog centered behind the desk
- head + chest + forepaws/arms visible
- desk occupies lower foreground
- enough headroom for ear motion
- shallow-perspective cinematic lens; avoid exaggerated wide-angle distortion

## Expression target

The character must look alive even while silent:

- breathing
- natural blinking
- tiny eye saccades
- subtle ear motion
- small head corrections
- mild chest/shoulder movement

During dialogue it must support:

- smile
- laugh
- listening focus
- thinking expression
- serious/concerned expression
- nods
- natural jaw/lip movement synchronized to Arabic speech

## Rig

At minimum:

- root / pelvis / spine / neck / head
- jaw
- left/right ears
- left/right eyelids
- left/right eyes
- brows / cheek or muzzle controls where topology allows
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
