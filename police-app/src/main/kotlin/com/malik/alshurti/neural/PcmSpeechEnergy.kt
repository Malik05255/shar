package com.malik.alshurti.neural

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Small, deterministic PCM envelope used by the local lip-sync path.
 *
 * Supertonic returns normalized float PCM, but loudness changes with voice/text. A fixed absolute
 * threshold therefore produces either a frozen mouth or constant chatter. We calibrate each
 * synthesized chunk from its own RMS distribution, then expose a normalized 0..1 speech energy.
 */
object PcmSpeechEnergy {
    data class Calibration(
        val noiseFloorRms: Float,
        val speechReferenceRms: Float,
    )

    fun calibrate(audio: FloatArray, sampleRate: Int): Calibration {
        if (audio.isEmpty() || sampleRate <= 0) return Calibration(0.002f, 0.04f)
        val window = max(1, sampleRate * CALIBRATION_WINDOW_MS / 1000)
        val hop = max(1, window / 2)
        val values = ArrayList<Float>()
        var start = 0
        while (start < audio.size) {
            values += rms(audio, start, (start + window).coerceAtMost(audio.size))
            start += hop
        }
        if (values.isEmpty()) return Calibration(0.002f, 0.04f)
        values.sort()
        val quiet = percentile(values, 0.18f)
        val voiced = percentile(values, 0.86f)
        val floor = max(MIN_NOISE_FLOOR, quiet * 0.72f)
        val reference = max(floor + MIN_DYNAMIC_RANGE, voiced)
        return Calibration(floor, reference)
    }

    fun normalizedAt(
        audio: FloatArray,
        sampleRate: Int,
        fraction: Float,
        calibration: Calibration,
    ): Float {
        if (audio.isEmpty() || sampleRate <= 0) return 0f
        val center = ((audio.lastIndex) * fraction.coerceIn(0f, 1f)).toInt()
        val halfWindow = max(1, sampleRate * PLAYBACK_WINDOW_MS / 2000)
        val from = (center - halfWindow).coerceAtLeast(0)
        val to = (center + halfWindow).coerceAtMost(audio.size)
        val current = rms(audio, from, to)
        val span = (calibration.speechReferenceRms - calibration.noiseFloorRms)
            .coerceAtLeast(MIN_DYNAMIC_RANGE)
        val linear = ((current - calibration.noiseFloorRms) / span).coerceIn(0f, 1f)
        // sqrt keeps quiet syllable tails visible without letting the calibrated floor chatter.
        return sqrt(linear)
    }

    fun smooth(previous: Float, current: Float): Float {
        val p = previous.coerceIn(0f, 1f)
        val c = current.coerceIn(0f, 1f)
        val currentWeight = if (c > p) ATTACK_WEIGHT else RELEASE_WEIGHT
        return (p * (1f - currentWeight) + c * currentWeight).coerceIn(0f, 1f)
    }

    fun isVoiced(energy: Float, wasVoiced: Boolean): Boolean {
        val threshold = if (wasVoiced) VOICE_RELEASE_THRESHOLD else VOICE_ATTACK_THRESHOLD
        return energy >= threshold
    }

    private fun rms(audio: FloatArray, from: Int, to: Int): Float {
        if (from >= to || from !in 0..audio.size || to !in 0..audio.size) return 0f
        var sum = 0.0
        var count = 0
        for (index in from until to) {
            val sample = audio[index]
            if (!sample.isFinite()) continue
            val clipped = sample.coerceIn(-1f, 1f).toDouble()
            sum += clipped * clipped
            count++
        }
        if (count == 0) return 0f
        return sqrt(sum / count).toFloat()
    }

    private fun percentile(sorted: List<Float>, quantile: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = ((sorted.lastIndex) * quantile.coerceIn(0f, 1f)).toInt()
        return sorted[index]
    }

    private const val CALIBRATION_WINDOW_MS = 40
    private const val PLAYBACK_WINDOW_MS = 46
    private const val MIN_NOISE_FLOOR = 0.0015f
    private const val MIN_DYNAMIC_RANGE = 0.008f
    private const val ATTACK_WEIGHT = 0.68f
    private const val RELEASE_WEIGHT = 0.28f
    private const val VOICE_ATTACK_THRESHOLD = 0.24f
    private const val VOICE_RELEASE_THRESHOLD = 0.14f
}
