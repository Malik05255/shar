package com.malik.alshurti

/**
 * Pure policy for recorded office Foley.
 *
 * Only cues backed by the reviewed CC0/Public-Domain recordings are enabled. Unsupported events
 * stay silent instead of falling back to generated tones or generic sound effects.
 */
internal object OfficeFoleyPolicy {
    fun isSupported(cue: OfficeCue): Boolean = when (cue) {
        OfficeCue.PHONE_RING,
        OfficeCue.DOOR_OPEN,
        OfficeCue.DOOR_CLOSE,
        OfficeCue.PAPER_RUSTLE -> true
        else -> false
    }

    fun cooldownMs(cue: OfficeCue): Long = when (cue) {
        OfficeCue.PHONE_RING -> 5_000L
        OfficeCue.DOOR_OPEN -> 1_250L
        OfficeCue.DOOR_CLOSE -> 1_500L
        OfficeCue.PAPER_RUSTLE -> 900L
        else -> Long.MAX_VALUE
    }

    fun baseVolume(cue: OfficeCue): Float = when (cue) {
        OfficeCue.PHONE_RING -> 0.44f
        OfficeCue.DOOR_OPEN -> 0.40f
        OfficeCue.DOOR_CLOSE -> 0.48f
        OfficeCue.PAPER_RUSTLE -> 0.24f
        else -> 0f
    }

    /** Keep physical events audible without letting the phone/door leak dominate speech or ASR. */
    fun phaseGain(phase: CallPhase): Float = when (phase) {
        CallPhase.STARTING -> 0.55f
        CallPhase.LISTENING -> 0.50f
        CallPhase.THINKING -> 0.70f
        CallPhase.SPEAKING -> 0.35f
        CallPhase.ERROR -> 0.40f
    }
}

internal class OfficeFoleyGate {
    private val lastPlayedAtMs = mutableMapOf<OfficeCue, Long>()

    fun shouldPlay(cue: OfficeCue, nowMs: Long): Boolean {
        if (!OfficeFoleyPolicy.isSupported(cue)) return false
        val previous = lastPlayedAtMs[cue]
        if (previous != null && nowMs - previous < OfficeFoleyPolicy.cooldownMs(cue)) return false
        lastPlayedAtMs[cue] = nowMs
        return true
    }

    fun reset() = lastPlayedAtMs.clear()
}
