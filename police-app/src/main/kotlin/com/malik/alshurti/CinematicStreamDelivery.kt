package com.malik.alshurti

/**
 * Delivery architecture for film-quality office life without growing the APK.
 *
 * The 3D production pipeline renders cinematic branches remotely. The phone streams only the
 * current branch plus a small prefetch window, keeps a bounded local cache, and discards old media.
 * Visual quality is never reduced to save package size.
 */
enum class CinematicCodec {
    HEVC_MAIN10,
    AV1_MAIN10,
    AVC_HIGH_FALLBACK
}

enum class CinematicTrackKind {
    AMBIENT_OFFICE,
    INTERACTION_TRANSITION,
    POLICE_REPLY,
    BACKGROUND_DIALOGUE,
    RETURN_TO_WORK
}

data class CinematicSegment(
    val id: String,
    val url: String,
    val sha256: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val codec: CinematicCodec,
    val kind: CinematicTrackKind,
    val entryPose: String,
    val exitPose: String,
    val audioStemIds: List<String> = emptyList(),
    val safeInterruptPointsMs: List<Long> = emptyList()
)

data class CinematicScenarioManifest(
    val scenarioId: String,
    val revision: Long,
    val openingSegmentId: String,
    val segments: List<CinematicSegment>,
    val fallbackSegmentId: String? = null
)

data class CinematicCachePolicy(
    val maxBytes: Long = 420L * 1024L * 1024L,
    val maxAgeHours: Int = 72,
    val prefetchSegments: Int = 3,
    val keepCurrentScenarioPinned: Boolean = true
)

object CinematicDeliveryPolicy {
    /** The APK contains code + a minimal bootstrap only; cinematic library stays remote. */
    const val BUNDLE_FULL_SCENARIO_LIBRARY_IN_APK = false

    /** Never switch to a lower-detail render merely because the APK/network budget is smaller. */
    const val ADAPTIVE_VISIBLE_QUALITY_DOWNGRADE_ALLOWED = false

    /** Prefer buffering over visibly lowering the mastered cinematic render. */
    const val BUFFER_INSTEAD_OF_DOWNGRADE = true

    /** Scene switches happen only at authored continuity points, never arbitrary video cuts. */
    const val REQUIRE_MATCHED_ENTRY_EXIT_POSES = true

    /** Background audio is transported as optional stems so it can duck when the observer speaks. */
    const val USE_SEPARATE_BACKGROUND_AUDIO_STEMS = true

    /** Publishing new office-life scenarios never requires shipping another Android build. */
    const val REMOTE_SCENARIO_UPDATES_ALLOWED = true
}

/**
 * Deterministic branch selector. Scenario AI selects intent; this resolver only chooses authored
 * segments whose start/end continuity matches the currently visible cinematic state.
 */
class CinematicBranchResolver {
    fun compatibleNext(
        current: CinematicSegment,
        candidates: List<CinematicSegment>,
        preferredKind: CinematicTrackKind
    ): CinematicSegment? {
        val exact = candidates.filter {
            it.kind == preferredKind &&
                it.entryPose == current.exitPose &&
                it.width == current.width &&
                it.height == current.height &&
                it.frameRate == current.frameRate
        }
        return exact.firstOrNull()
    }

    fun nearestSafeInterrupt(segment: CinematicSegment, positionMs: Long): Long? =
        segment.safeInterruptPointsMs
            .filter { it >= positionMs }
            .minOrNull()
}
