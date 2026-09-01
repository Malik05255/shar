package com.malik.alshurti.neural

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PcmSpeechEnergyTest {
    @Test
    fun silenceStaysUnvoiced() {
        val audio = FloatArray(16_000)
        val calibration = PcmSpeechEnergy.calibrate(audio, 16_000)
        val energy = PcmSpeechEnergy.normalizedAt(audio, 16_000, 0.5f, calibration)
        assertTrue(energy <= 0.001f)
        assertFalse(PcmSpeechEnergy.isVoiced(energy, false))
    }

    @Test
    fun voicedWindowRisesAboveAttackThreshold() {
        val sampleRate = 16_000
        val audio = FloatArray(sampleRate) { index ->
            if (index in 4_000 until 12_000) {
                (0.22 * sin(2.0 * PI * 180.0 * index / sampleRate)).toFloat()
            } else {
                0f
            }
        }
        val calibration = PcmSpeechEnergy.calibrate(audio, sampleRate)
        val energy = PcmSpeechEnergy.normalizedAt(audio, sampleRate, 0.5f, calibration)
        assertTrue("expected voiced energy, got $energy", energy > 0.55f)
        assertTrue(PcmSpeechEnergy.isVoiced(energy, false))
    }

    @Test
    fun attackIsFasterThanReleaseAndHysteresisPreventsChatter() {
        val attack = PcmSpeechEnergy.smooth(0f, 1f)
        val release = PcmSpeechEnergy.smooth(1f, 0f)
        assertTrue(attack > 0.6f)
        assertTrue(release > 0.6f)
        assertTrue(PcmSpeechEnergy.isVoiced(0.30f, false))
        assertTrue(PcmSpeechEnergy.isVoiced(0.18f, true))
        assertFalse(PcmSpeechEnergy.isVoiced(0.18f, false))
        assertFalse(PcmSpeechEnergy.isVoiced(0.10f, true))
    }
}
