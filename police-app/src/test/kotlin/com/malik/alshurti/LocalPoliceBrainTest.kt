package com.malik.alshurti

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPoliceBrainTest {
    @Test
    fun parentTookToyGetsCalmResponse() = runTest {
        val reply = DeterministicPoliceBrain().reply("يا شرطي بابا أخذ كرتي")
        assertTrue(reply.text.contains("بهدوء"))
        assertFalse(reply.text.contains("سجن"))
        assertFalse(reply.text.contains("دورية"))
    }

    @Test
    fun realDangerRoutesChildToAdultAndEmergencyHelp() = runTest {
        val reply = DeterministicPoliceBrain().reply("في واحد يهددني بسكين")
        assertTrue(reply.text.contains("شخص بالغ"))
        assertTrue(reply.text.contains("الطوارئ"))
        assertTrue(reply.mood == DogMood.SERIOUS)
    }

    @Test
    fun siblingViolenceIsDeEscalated() = runTest {
        val reply = DeterministicPoliceBrain().reply("أخوي ضربني")
        assertTrue(reply.text.contains("ابتعد"))
        assertTrue(reply.text.contains("بدون ضرب"))
    }
}