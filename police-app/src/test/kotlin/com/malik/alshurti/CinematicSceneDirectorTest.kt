package com.malik.alshurti

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinematicSceneDirectorTest {
    @Test
    fun firstChildReplyAlwaysGetsStandingPerformance() {
        val director = CinematicSceneDirector(seed = 42L)

        assertTrue(director.shouldStandForReply(completedPoliceTurns = 1, mood = DogMood.CALM))
        assertFalse(director.shouldStandForReply(completedPoliceTurns = 2, mood = DogMood.CALM))
    }

    @Test
    fun seriousConversationNeverTriggersIntrusiveOfficeBeat() {
        val director = CinematicSceneDirector(seed = 7L)

        repeat(100) {
            val beat = director.nextBeat(DogMood.SERIOUS)
            assertTrue(
                beat == CinematicSceneDirector.Beat.QUIET ||
                    beat == CinematicSceneDirector.Beat.PAPER
            )
        }
    }

    @Test
    fun majorEventsNeverRunBackToBack() {
        val director = CinematicSceneDirector(seed = 99L)
        val major = setOf(
            CinematicSceneDirector.Beat.PHONE,
            CinematicSceneDirector.Beat.APPROACH,
            CinematicSceneDirector.Beat.DOOR
        )

        var previous = CinematicSceneDirector.Beat.QUIET
        repeat(200) {
            val current = director.nextBeat(DogMood.CALM)
            assertFalse(previous in major && current in major)
            previous = current
        }
    }
}
