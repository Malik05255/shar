# Runtime 3D — Free-First / No-Quality-Regression Policy

This policy is mandatory for the cinematic office project.

## Red lines

1. **Visible quality may never be reduced to save money or credits.** The approved cinematic reference remains the minimum visual benchmark.
2. **Zero-cost and open-source paths are always tried first.** A paid API may not become a required dependency when an acceptable local/open alternative exists.
3. **No automatic paid generation.** Workflows must not consume purchased credits, trigger paid overage, or require a credit card unless the project owner explicitly overrides this policy for a specific run.
4. **Free trials/credits are secondary accelerators, not the foundation.** Their exhaustion must not block the project.
5. **No low-poly hero substitution, sprites, billboards, fake 2D zooms, or texture-resolution downgrades.**
6. **No production activation from structural validation alone.** A candidate must also pass visual identity, fur/uniform/material, rig, animation, lip-sync, and physical-device acceptance checks.
7. **The hero dog must remain a real rigged GLB.** Background actors and props must remain independently animated GLBs.
8. **Arabic speech quality is a production gate.** Robotic/broken Arabic is rejected even if technically functional.
9. **Every external service must be classified before use:** unlimited/free local, recurring free quota, one-time trial, preview-only, or paid.
10. **If a free service prevents export/download, it is reference-only and cannot be the production source.**

## Preferred zero-cost stack

### 3D generation / reconstruction
- Tencent Hunyuan3D-2.1 locally: open framework, high-fidelity image-to-3D and production-oriented PBR pipeline. Official requirements: ~10 GB VRAM for shape, ~21 GB for texture, ~29 GB combined.
- Microsoft TRELLIS locally: MIT-licensed high-quality image/text-to-3D; requires >=16 GB NVIDIA VRAM.
- Stability AI Stable Fast 3D locally: GLB export, UV/material prediction, ~6 GB VRAM; use as a candidate/fallback, never as an excuse to reduce hero quality.

### Free compute before paid compute
- Kaggle Notebooks: free GPU quota, typically ~30 GPU hours/week; best for SF3D and Hunyuan shape experiments.
- Lightning AI Free: recurring free credits/GPU time; prioritize >=24 GB GPU for Hunyuan texture and >=40 GB GPU when available for the full pipeline.
- Hugging Face ZeroGPU: small daily free quota; use only for jobs that fit the free duration.
- Google Colab Free: opportunistic fallback only; GPU availability/limits are dynamic and not guaranteed.

### Rigging / animation
- Blender + Rigify: free/open source; includes Basic Quadruped, Cat, Wolf, Horse metarigs. This is the default hero-dog rigging route.
- Mixamo: free with Adobe ID for human/biped staff only; never use for the dog.
- Rokoko Starter / Plask / DeepMotion free allocations: optional sources for human staff motion only.
- Cascadeur Free may be used for experimentation, but its free tier cannot export normal production animation formats; therefore it cannot be a required production step.

### Materials / office assets
- Poly Haven: CC0 HDRIs, PBR textures and high-quality 3D models; no paywall/signup required. Prefer for office materials, lighting references and suitable props.
- Blender: authored PBR finishing and texture/material correction.

### Lip sync
- Rhubarb Lip Sync: MIT; use the language-independent phonetic recognizer for Arabic and map timing output to the hero's authored 3D viseme shape keys.

### Arabic TTS
- SILMA TTS: Arabic/English, voice cloning, Apache-2.0 model weights, commercial-friendly. Preferred local zero-credit candidate for replacing robotic Arabic speech.
- Other TTS systems may be evaluated only if they meet or exceed SILMA's Arabic naturalness without adding a paid runtime dependency.

## Free-credit services — use only after unlimited/local options

1. Tripo Studio Free: recurring free monthly credits. Useful for comparison candidates and experiments; not a required dependency.
2. Tripo OpenAPI promotional credits: use only if legitimately present in the existing API account. Never create additional accounts to evade limits.
3. Masterpiece X: one-time free signup credits; comparison only until export/quality/licensing are verified.
4. Meshy Free: credits are useful for visual evaluation, but free generated-model download restrictions make it unsuitable as the production GLB source.
5. Sloyd Guest / Hyper3D Rodin Free: preview/exploration only when export requires payment.

## Acceptance order

For every generated hero candidate:

1. Exact character identity and silhouette against the approved reference.
2. Face/muzzle/ears/eyes and fur pattern.
3. Navy police uniform, badge placement, material response.
4. Geometry detail at phone viewing distance.
5. UV/PBR quality: base color, normal, roughness, metallic where applicable.
6. Deformation quality after quadruped rigging.
7. Facial/muzzle visemes and blink/eye controls.
8. Required named animation clips from `CONTENT_PACK_SPEC.md`.
9. Runtime layering without pops/snaps.
10. Physical Android device comparison; only then can `manifest.json` become enabled.

## Paid-service kill switch

A paid provider is disabled by default. Its presence in the repository is allowed only as an optional fallback. CI must not automatically spend credits. Any paid use requires a separate, explicit owner decision for that exact operation.
