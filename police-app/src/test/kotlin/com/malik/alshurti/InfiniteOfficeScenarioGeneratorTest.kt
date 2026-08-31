package com.malik.alshurti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val signatures = (0 until 24).map {
            val plan = generator.next(observerEngaged = false)
            val animation = plan.commands
                .map { command -> "${command.actor}:${command.clip}:${command.channel}:${command.targetActor}" }
                .sorted()
                .joinToString("|")
            val sound = plan.sounds
                .map { command -> "${command.sound}:${command.zone}" }
                .sorted()
                .joinToString("|")
            "$animation#$sound"
        }
        assertEquals(signatures.size, signatures.toSet().size)
    }

    @Test
    fun semanticBeatDoesNotImmediatelyRepeat() {
        val generator = InfiniteOfficeScenarioGenerator(seed = 8080L)
        val reasons = (0 until 40).map { generator.next(false).reason }
        reasons.zipWithNext().forEach { (previous, next) ->
            val previousSemantic = previous.substringAfter('-', "").substringAfter('-')
            val nextSemantic = next.substringAfter('-', "").substringAfter('-')
            assertNotEquals(previousSemantic, nextSemantic)
        }
    }

    @Test
    fun officeNeverStaysWithoutApprovedRecordedSoundForTwoConsecutiveBeats() {
        val generator = InfiniteOfficeScenarioGenerator(seed = 6006L)
        val approved = setOf(
            OfficeSoundId.PHONE_RING,
            OfficeSoundId.DOOR_OPEN,
            OfficeSoundId.DOOR_CLOSE,
            OfficeSoundId.PAGE_TURN,
            OfficeSoundId.PAPER_HANDLE
        )
        var previousWasSilent = false
        repeat(50) {
            val plan = generator.next(false)
            val silent = plan.sounds.none { it.sound in approved }
            assertFalse("Two consecutive office beats had no approved recorded sound", previousWasSilent && silent)
            previousWasSilent = silent
        }
    }
}
