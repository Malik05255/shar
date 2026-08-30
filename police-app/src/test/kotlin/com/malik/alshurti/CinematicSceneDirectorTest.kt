package com.malik.alshurti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CinematicSceneDirectorTest {
    @Before
    fun resetContext() {
        SceneContextRegistry.reset()
    }

    @Test
    fun firstNormalChildReplyGetsStandingPerformance() {
        val director = CinematicSceneDirector(seed = 42L)
        SceneContextRegistry.observe("وش تسوي يا شرطي")

        assertTrue(director.shouldStandForReply(completedPoliceTurns = 1, mood = DogMood.CALM))
        assertFalse(director.shouldStandForReply(completedPoliceTurns = 2, mood = DogMood.CALM))
    }

    @Test
    fun seriousContextSuppressesStandingShowcase() {
        val director = CinematicSceneDirector(seed = 42L)
        SceneContextRegistry.observe("في واحد يهددني بسكين")

        assertFalse(director.shouldStandForReply(completedPoliceTurns = 1, mood = DogMood.SERIOUS))
    }

    @Test
    fun safetyConversationNeverTriggersIntrusiveOfficeBeat() {
        val director = CinematicSceneDirector(seed = 7L)
        SceneContextRegistry.observe("في حريق وانا خايف")

        repeat(100) {
            assertEquals(CinematicSceneDirector.Beat.QUIET, director.nextBeat(DogMood.SERIOUS))
        }
    }

    @Test
    fun schoolContextStaysWithinPaperOrQuietScope() {
        val director = CinematicSceneDirector(seed = 21L)
        SceneContextRegistry.observe("عندي واجب في المدرسة")

        repeat(100) {
            val beat = director.nextBeat(DogMood.CALM)
            assertTrue(
                beat == CinematicSceneDirector.Beat.QUIET ||
                    beat == CinematicSceneDirector.Beat.PAPER
            )
        }
    }

    @Test
    fun literalPhoneCueMatchesCurrentEventAndIsConsumed() {
        val director = CinematicSceneDirector(seed = 11L)
        SceneContextRegistry.observe("الجوال يرن")

        assertEquals(CinematicSceneDirector.Beat.PHONE, director.nextBeat(DogMood.CALM))
        assertFalse(director.nextBeat(DogMood.CALM) == CinematicSceneDirector.Beat.PHONE)
    }

    @Test
    fun explicitApproachIsBlockedWhenChildSaysDoNotApproach() {
        val director = CinematicSceneDirector(seed = 13L)
        SceneContextRegistry.observe("لا تقرب مني انا خايف")

        repeat(50) {
            assertFalse(director.nextBeat(DogMood.CALM) == CinematicSceneDirector.Beat.APPROACH)
        }
    }

    @Test
    fun majorEventsNeverRunBackToBackInGeneralConversation() {
        val director = CinematicSceneDirector(seed = 99L)
        SceneContextRegistry.observe("سولف معي")
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
