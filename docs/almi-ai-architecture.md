# ALMI_AI architecture

ALMI_AI keeps the useful separation principle from the upstream `Skykai521/VibeApp` project without carrying its on-device Android app-build engine.

## Why ALMI_AI is different from VibeApp

`Skykai521/VibeApp` is a natural-language **application builder**:

```
Natural-language request
  -> Agent gateway/router
  -> Agent loop + filesystem tools
  -> generated Java/XML
  -> AAPT2 / javac / D8 / APK signing
```

ALMI_AI is a **product-understanding and media-generation app**:

```
Person photo + product URL/garment image
  -> product understanding
  -> image generation
  -> optional video generation
```

The build engine, project workspace, coding agent loop and plugin runtime are intentionally not part of ALMI_AI.

## Core rule: each AI task has its own gateway

Do not put product-page parsing, image generation and video generation into one repository.
They are different API contracts and fail for different reasons.

```
TryOnScreen
    |
TryOnViewModel
    |
    +-- ProductPreviewRepository
    |      |
    |      +-- ProductPageExtractor        deterministic/local fast path
    |      |      +-- schema.org Product JSON-LD
    |      |      +-- OpenGraph/product meta
    |      |      +-- semantic/lazy image extraction
    |      |
    |      +-- ProductAiGateway            recovery/enrichment path
    |             +-- active AI mode
    |             +-- OpenRouter web_fetch when available
    |             +-- factual JSON merge
    |
    +-- MediaGenerationGateway
           +-- image generation endpoint
           +-- asynchronous video endpoint
           +-- provider/model/key fallback
```

## Product URL pipeline

1. Normalize and validate the public HTTP(S) URL.
2. Reject localhost/private-network destinations.
3. Fetch a bounded page body with browser-like request headers.
4. Prefer `schema.org/Product` JSON-LD over generic metadata.
5. Extract title, description, brand, price, currency, color, SKU and product images.
6. Score local extraction confidence.
7. If the result is strong enough, return it immediately with no AI cost.
8. If it is blocked/incomplete, ask `ProductAiGateway` to recover/enrich it.
9. When OpenRouter is active, the gateway can use `openrouter:web_fetch` restricted to the pasted product host.
10. Merge AI fields conservatively. Never replace a reliable local field with an unverified guess.

## Media pipeline

Image and video are independent capabilities.
A custom provider is not rejected because its unrelated video or analysis configuration is blank.

### Image

```
person reference + garment reference
  -> MediaGenerationGateway.generateImage
  -> configured image endpoint/model
  -> saved local result URI
```

### Video

```
generated try-on image
  -> MediaGenerationGateway.generateVideo
  -> configured asynchronous video endpoint/model
  -> poll job
  -> download and save local MP4
```

Do not hardcode model-specific video duration/resolution/audio values into the common gateway. Read model capabilities before adding such controls.

## AI modes

### Automatic

- OpenRouter user keys are stored in the encrypted `ApiKeyVault`.
- Product-link analysis uses a text route and web fetch.
- Image/video models are discovered by media capability.
- Keys and models are tried in fallback order.

### Custom

The user controls three independent contracts:

- Product analysis endpoint + text model
- Image endpoint + image model
- Video endpoint + video model

The custom provider API key is stored in `ApiKeyVault` with Android Keystore AES/GCM. Non-secret endpoint/model settings stay in normal preferences.

## Security boundaries

- User-selected photos are sent only when generation starts.
- API secrets are encrypted at rest with Android Keystore.
- Product URLs must be public HTTP(S) URLs; local/private network targets are rejected.
- AI product extraction is instructed to return empty fields instead of inventing product facts.
- The current repository signing key is a **development update-channel key**, not a production publishing credential. Production distribution must use a private CI secret and/or Google Play App Signing.

## Testing priorities

At minimum keep regression tests for:

- schema.org Product extraction
- HTML/OpenGraph fallback
- image candidate selection
- public URL normalization
- localhost/private-network rejection
- provider capability validation
- result merge behavior

The CI Release build, lint and unit tests must remain green before distributing a new APK.
