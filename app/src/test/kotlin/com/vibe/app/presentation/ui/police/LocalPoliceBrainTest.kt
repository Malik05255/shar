package com.vibe.app.presentation.ui.police

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPoliceBrainTest {

    @Test
    fun `parent took toy gets calm non threatening response`() = runTest {
        val brain = LocalPoliceBrain()
        val reply = brain.reply("يا شرطي بابا أخذ كرتي")

        assertTrue(reply.text.contains("بهدوء"))
        assertFalse(reply.text.contains("سجن"))
        assertFalse(reply.text.contains("دورية"))
    }

    @Test
    fun `real danger routes child to trusted adult and emergency help`() = runTest {
        val brain = LocalPoliceBrain()
        val reply = brain.reply("في واحد يهددني بسكين")

        assertTrue(reply.text.contains("شخص بالغ"))
        assertTrue(reply.text.contains("الطوارئ"))
        assertTrue(reply.mood == DogMood.SERIOUS)
    }

    @Test
    fun `sibling violence is de escalated`() = runTest {
        val brain = LocalPoliceBrain()
        val reply = brain.reply("أخوي ضربني")

        assertTrue(reply.text.contains("ابتعد"))
        assertTrue(reply.text.contains("بدون ضرب"))
    }
}
