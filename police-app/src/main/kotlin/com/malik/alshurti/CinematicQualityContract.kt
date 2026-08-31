package com.malik.alshurti

/**
 * Non-negotiable visual-quality contract for the living 3D office.
 *
 * Runtime 3D is an implementation choice, not a downgrade. The visual benchmark is the existing
 * photoreal cinematic MP4 master: the production renderer is not considered ready until the dog,
 * lighting, materials, framing and human motion read at the same cinematic quality on a phone.
 */
object CinematicQualityContract {
    const val TARGET_ASPECT_RATIO = 16f / 9f
    const val TARGET_FRAME_RATE = 30

    // Hero character is never reduced to a sprite/billboard/2D cutout in production.
    const val HERO_MUST_BE_RIGGED_3D = true
    const val HERO_PBR_REQUIRED = true
    const val HERO_MIN_TEXTURE_EDGE = 2048

    // Background may use invisible optimization only; silhouettes/lighting cannot visibly pop.
    const val BACKGROUND_LOD_ALLOWED = true
    const val HERO_LOD_REDUCTION_ALLOWED = false
    const val VISIBLE_LOD_POP_ALLOWED = false

    // Full-scene MP4s remain a reference/fallback during migration, never the scenario system.
    const val MP4_IS_VISUAL_BENCHMARK = true
    const val NEW_SCENARIO_REQUIRES_NEW_MP4 = false

    val requiredRenderFeatures = setOf(
        "PBR",
        "IBL",
        "soft-dynamic-shadows",
        "baked-global-illumination",
        "reflection-probes",
        "filmic-tone-mapping",
        "anti-aliasing",
        "depth-aware-camera",
        "animation-blending",
        "skeletal-facial-motion"
    )

    val forbiddenProductionShortcuts = setOf(
        "hero-2d-sprite",
        "fake-zoom-as-body-motion",
        "full-scene-video-per-scenario",
        "hard-animation-cuts",
        "synchronized-background-loop",
        "constant-camera-stare",
        "synthetic-continuous-hum"
    )
}

/**
 * Cinematic motion rules. These are deliberately stricter than a game animation system because
 * the target is observational realism: the viewer should feel they are watching an occupied
 * security office rather than interacting with an avatar waiting for input.
 */
object CinematicHumanMotionContract {
    const val EYES_LEAD_HEAD_MS = 90L
    const val HEAD_LEADS_TORSO_MS = 140L
    const val MIN_IDLE_VARIATION_MS = 1_600L
    const val MAX_IDLE_VARIATION_MS = 7_000L

    val requiredMicroMotion = setOf(
        "irregular-blink",
        "eye-saccades",
        "breathing",
        "weight-shift",
        "asymmetric-hand-rest",
        "small-head-corrections",
        "non-periodic-idle-timing"
    )

    val interactionSequence = listOf(
        "voice-activity-detected",
        "eyes-to-observer",
        "head-turn",
        "torso-adjustment-if-needed",
        "listen",
        "reply",
        "attention-break",
        "return-to-work"
    )
}
