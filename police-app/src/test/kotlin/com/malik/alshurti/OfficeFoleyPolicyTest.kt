package com.malik.alshurti

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeFoleyPolicyTest {
    @Test
    fun `only reviewed physical cues are supported`() {
        assertTrue(OfficeFoleyPolicy.isSupported(OfficeCue.PHONE_RING))
        assertTrue(OfficeFoleyPolicy.isSupported(OfficeCue.DOOR_OPEN))
        assertTrue(OfficeFoleyPolicy.isSupported(OfficeCue.DOOR_CLOSE))
        assertTrue(OfficeFoleyPolicy.isSupported(OfficeCue.PAPER_RUSTLE))
        assertFalse(OfficeFoleyPolicy.isSupported(OfficeCue.FOOTSTEPS))
        assertFalse(OfficeFoleyPolicy.isSupported(OfficeCue.STAFF_SPEAK))
    }

    @Test
    fun `rate gate blocks duplicate ring until cooldown expires`() {
        val gate = OfficeFoleyGate()
        assertTrue(gate.shouldPlay(OfficeCue.PHONE_RING, 10_000L))
        assertFalse(gate.shouldPlay(OfficeCue.PHONE_RING, 14_999L))
        assertTrue(gate.shouldPlay(OfficeCue.PHONE_RING, 15_000L))
    }

    @Test
    fun `different physical cues do not block each other`() {
        val gate = OfficeFoleyGate()
        assertTrue(gate.shouldPlay(OfficeCue.DOOR_OPEN, 1_000L))
        assertTrue(gate.shouldPlay(OfficeCue.DOOR_CLOSE, 1_050L))
        assertTrue(gate.shouldPlay(OfficeCue.PAPER_RUSTLE, 1_100L))
    }

    @Test
    fun `unsupported cue can never pass rate gate`() {
        val gate = OfficeFoleyGate()
        assertFalse(gate.shouldPlay(OfficeCue.FOOTSTEPS, 1_000L))
        assertFalse(gate.shouldPlay(OfficeCue.STAFF_PASS, 1_000L))
    }
}
