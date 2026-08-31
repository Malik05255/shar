package com.malik.alshurti

/**
 * Non-negotiable visual-quality contract.
 *
 * We are allowed to change delivery/storage architecture, but not the visible result. The existing
 * cinematic MP4 master is the minimum benchmark: dog identity, fur, uniform, office lighting,
 * materials, lens/framing, depth, human motion and background detail must not be visibly reduced.
 */
object CinematicQualityContract {
    const val TARGET_ASPECT_RATIO = 16f / 9f
    const val TARGET_FRAME_RATE = 30
    const val DELIVERY_MUST_MATCH_CINEMATIC_MASTER = true

    // Authoring/source remains independent high-quality 3D actors. Delivery to the phone may be a
    // cloud-rendered cinematic stream so the APK does not have to contain the rendered footage.
    const val SOURCE_HERO_MUST_BE_RIGGED_3D = true
    const val SOURCE_HERO_PBR_REQUIRED = true
    const val SOURCE_HERO_MIN_TEXTURE_EDGE = 2048
    const val CLOUD_PRERENDERED_DELIVERY_ALLOWED = true

    // Never trade visible quality for APK size. Storage/network optimization happens after render.
    const val ALLOW_VISIBLE_QUALITY_REDUCTION_FOR_SIZE = false
    const val ALLOW_HERO_TEXTURE_DOWNSCALE_FOR_SIZE = false
    const val ALLOW_SIMPLIFIED_HERO_MODEL_FOR_SIZE = false

    // Full-scene cinematic output is allowed as a DELIVERY format, but scenarios are still authored
    // from reusable 3D actors. New content can be published remotely without an APK update.
    const val MP4_IS_VISUAL_BENCHMARK = true
    const val NEW_SCENARIO_REQUIRES_APP_UPDATE = false
    const val NEW_SCENARIO_MUST_BE_BUNDLED_IN_APK = false

    val requiredRenderFeatures = setOf(
        "PBR",
        "IBL",
        "soft-dynamic-shadows",
        "global-illumination",
        "reflection-probes",
        "filmic-tone-mapping",
        "anti-aliasing",
        "depth-aware-camera",
        "animation-blending",
        "skeletal-facial-motion",
        "cinematic-motion-blur-when-appropriate",
        "consistent-color-grade"
    )

    val forbiddenProductionShortcuts = setOf(
        "hero-2d-sprite",
        "fake-zoom-as-body-motion",
        "visible-low-poly-hero",
        "lower-resolution-hero-for-apk-size",
        "hard-animation-cuts",
        "synchronized-background-loop",
        "constant-camera-stare",
        "synthetic-continuous-hum",
        "visible-quality-drop-between-segments"
    )
}

/**
 * Cinematic motion rules. The target is observational realism, not game-avatar responsiveness.
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
