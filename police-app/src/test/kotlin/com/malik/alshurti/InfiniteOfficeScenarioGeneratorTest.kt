package com.malik.alshurti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InfiniteOfficeScenarioGeneratorTest {
    @Test
    fun silentObserverDoesNotBecomeCameraFocus() {
        val generator = InfiniteOfficeScenarioGenerator(seed = 441L)
        repeat(40) {
            val plan = generator.next(observerEngaged = false)
            assertTrue(plan.keepWorldRunning)
            assertFalse(
                plan.commands.any {
                    it.actor == SceneActorId.POLICE_DOG && it.clip == "LookAtCamera"
                }
            )
        }
    }

    @Test
    fun recentOfficeBeatsDoNotRecycleTheirFullSignature() {
        val generator = InfiniteOfficeScenarioGenerator(seed = 90210L)
        val signatures = (0 until 18).map {
            generator.next(observerEngaged = false).commands
                .map { command -> "${command.actor}:${command.clip}:${command.channel}:${command.targetActor}" }
                .sorted()
                .joinToString("|")
        }
        assertEquals(signatures.size, signatures.toSet().size)
    }
}
