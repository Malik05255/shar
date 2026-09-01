package com.malik.alshurti

import kotlin.random.Random

/**
 * Persistent fictional security-office simulation.
 *
 * There is no finite scene list and no video timeline. Every ambient beat is composed from reusable
 * actors/animations by InfiniteOfficeScenarioGenerator and published to RuntimeOfficePlanBus. The
 * observer can interrupt the foreground officer, but the world itself keeps a continuous clock.
 */
class LivingOfficeWorld(seed: Long = System.nanoTime()) {
    enum class Opening {
        MORNING_PAPERWORK,
        QUIET_DESK_CONVERSATION,
        FILE_HANDOFF,
        PRINTER_RUN,
        SHIFT_CROSSING,
        PHONE_AT_BACKGROUND_DESK
    }

    enum class Engagement {
        UNAWARE_OF_OBSERVER,
        NOTICED_SPEECH,
        ENGAGED_WITH_OBSERVER,
        RETURNING_TO_WORK
    }

    private var random = Random(seed)
    private var generator = InfiniteOfficeScenarioGenerator(seed xor WORLD_SEED_SALT)
    private var engagement = Engagement.UNAWARE_OF_OBSERVER

    fun reset(seed: Long = System.nanoTime()) {
        random = Random(seed)
        generator.reset(seed xor WORLD_SEED_SALT)
        engagement = Engagement.UNAWARE_OF_OBSERVER
        RuntimeOfficePlanBus.clear()
    }

    /** Every app entry begins with a fresh composed office beat, not a prerecorded opening. */
    fun openingPlan(): RuntimeScenarioPlan {
        engagement = Engagement.UNAWARE_OF_OBSERVER
        return RuntimeOfficePlanBus.publish(generator.next(observerEngaged = false))
    }

    /** Unbounded local fallback: this can produce the next beat forever without network access. */
    fun nextAmbientPlan(): RuntimeScenarioPlan = RuntimeOfficePlanBus.publish(
        generator.next(observerEngaged = engagement == Engagement.ENGAGED_WITH_OBSERVER)
    )

    /** Called the instant real voice activity starts, before transcription is complete. */
    fun onObserverSpeechStarted(): RuntimeScenarioPlan {
        engagement = Engagement.NOTICED_SPEECH
        return RuntimeOfficePlanBus.publish(
            RuntimeScenarioPlan(
                durationHintMs = 1_100L,
                reason = "observer-speech-interruption",
                keepWorldRunning = true,
                commands = listOf(
                    SceneAnimationCommand(SceneActorId.POLICE_DOG, "SetFileDown", AnimationChannel.HANDS, blendMs = 180),
                    SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtCamera", AnimationChannel.GAZE, blendMs = 120),
                    SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtCamera", AnimationChannel.HEAD, blendMs = 260, delayMs = 90),
                    SceneAnimationCommand(SceneActorId.POLICE_DOG, "Listen", AnimationChannel.BODY, loop = true, blendMs = 360, delayMs = 180),
                    // Background actors do not freeze just because the foreground officer noticed us.
                    SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Type", AnimationChannel.HANDS, loop = true, playbackRate = 0.94f),
                    SceneAnimationCommand(SceneActorId.STAFF_MALE_01, "IdleDesk", AnimationChannel.BODY, loop = true),
                    SceneAnimationCommand(SceneActorId.STAFF_MALE_02, "Read", AnimationChannel.HANDS, loop = true, delayMs = 250L)
                )
            )
        )
    }

    fun whileObserverIsTalking(): RuntimeScenarioPlan {
        engagement = Engagement.ENGAGED_WITH_OBSERVER
        return RuntimeOfficePlanBus.publish(
            RuntimeScenarioPlan(
                durationHintMs = 8_000L,
                reason = "listen-with-independent-background-life",
                keepWorldRunning = true,
                commands = listOf(
                    SceneAnimationCommand(SceneActorId.POLICE_DOG, "Listen", AnimationChannel.BODY, loop = true),
                    SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtCamera", AnimationChannel.GAZE, loop = true, blendMs = 120),
                    SceneAnimationCommand(SceneActorId.POLICE_DOG, "Breathing", AnimationChannel.BODY, loop = true, playbackRate = 0.92f),
                    SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Type", AnimationChannel.HANDS, loop = true, playbackRate = 0.88f),
                    SceneAnimationCommand(SceneActorId.STAFF_MALE_02, "Read", AnimationChannel.HANDS, loop = true, playbackRate = 0.92f),
                    SceneAnimationCommand(SceneActorId.STAFF_MALE_01, "IdleDesk", AnimationChannel.BODY, loop = true)
                )
            )
        )
    }

    fun returnToOfficeWork(): RuntimeScenarioPlan {
        engagement = Engagement.RETURNING_TO_WORK
        val returnPlan = RuntimeScenarioPlan(
            durationHintMs = 2_800L,
            reason = "return-to-independent-office-work",
            keepWorldRunning = true,
            commands = listOf(
                SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtDesk", AnimationChannel.GAZE, blendMs = 210),
                SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtDesk", AnimationChannel.HEAD, blendMs = 330, delayMs = 120),
                SceneAnimationCommand(SceneActorId.POLICE_DOG, "ReachFile", AnimationChannel.HANDS, blendMs = 320, delayMs = 300),
                SceneAnimationCommand(SceneActorId.POLICE_DOG, "IdleWork", AnimationChannel.BODY, loop = true, blendMs = 480, delayMs = 620),
                SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Type", AnimationChannel.HANDS, loop = true),
                SceneAnimationCommand(SceneActorId.STAFF_MALE_02, "Read", AnimationChannel.HANDS, loop = true, delayMs = 400L)
            ),
            sounds = listOf(
                SpatialSoundCommand(OfficeSoundId.PAPER_HANDLE, OfficeZone.POLICE_DESK, delayMs = 540L, gain = 0.12f)
            )
        )
        engagement = Engagement.UNAWARE_OF_OBSERVER
        return RuntimeOfficePlanBus.publish(returnPlan)
    }

    /** AI plans are optional embellishment. Publishing one never changes the world clock. */
    fun publishExternalPlan(plan: RuntimeScenarioPlan): RuntimeScenarioPlan =
        RuntimeOfficePlanBus.publish(plan)

    private companion object {
        const val WORLD_SEED_SALT = 0x51A7E0FFL
    }
}
