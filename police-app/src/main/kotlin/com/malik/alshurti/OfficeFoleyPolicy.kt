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
        OfficeCue.PHONE_RING -> 8_000L
        OfficeCue.DOOR_OPEN -> 2_000L
        OfficeCue.DOOR_CLOSE -> 2_000L
        OfficeCue.PAPER_RUSTLE -> 2_200L
        else -> Long.MAX_VALUE
    }

    /**
     * These are intentionally audible on a normal phone speaker. The previous values were mixed
     * like distant ambience and could be effectively inaudible once multiplied by phase gain.
     */
    fun baseVolume(cue: OfficeCue): Float = when (cue) {
        OfficeCue.PHONE_RING -> 0.82f
        OfficeCue.DOOR_OPEN -> 0.68f
        OfficeCue.DOOR_CLOSE -> 0.76f
        OfficeCue.PAPER_RUSTLE -> 0.58f
        else -> 0f
    }

    /** Keep physical events below dialogue, but never reduce them to near-silence. */
    fun phaseGain(phase: CallPhase): Float = when (phase) {
        CallPhase.STARTING -> 0.82f
        CallPhase.LISTENING -> 0.78f
        CallPhase.THINKING -> 0.82f
        CallPhase.SPEAKING -> 0.42f
        CallPhase.ERROR -> 0.66f
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
