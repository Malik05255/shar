package com.malik.alshurti

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeWorldSchedulerTest {
    private val scheduler = OfficeWorldScheduler()

    @Test
    fun seatedStaffStandsBeforeLocomotion() {
        val actor = SceneActorId.STAFF_MALE_01
        val plan = RuntimeScenarioPlan(
            commands = listOf(
                SceneAnimationCommand(
                    actor = actor,
                    clip = "Walk",
                    channel = AnimationChannel.LOCOMOTION,
                    targetActor = SceneActorId.PRINTER
                )
            ),
            durationHintMs = 5_000L
        )
        val scheduled = scheduler.schedule(plan, snapshot(observerEngaged = false, seatedActor = actor))
        val stand = scheduled.commands.indexOfFirst { it.actor == actor && it.clip == "StandUp" }
        val walk = scheduled.commands.indexOfFirst { it.actor == actor && it.clip == "Walk" }
        assertTrue(stand >= 0)
        assertTrue(walk > stand)
        assertTrue(scheduled.commands[walk].delayMs >= 650L)
    }

    @Test
    fun silentObserverCannotReceiveCameraLook() {
        val plan = RuntimeScenarioPlan(
            commands = listOf(
                SceneAnimationCommand(
                    actor = SceneActorId.POLICE_DOG,
                    clip = "LookAtCamera",
                    channel = AnimationChannel.HEAD
                ),
                SceneAnimationCommand(
                    actor = SceneActorId.POLICE_DOG,
                    clip = "ReviewFile",
                    channel = AnimationChannel.HANDS,
                    loop = true
                )
            ),
            durationHintMs = 4_000L
        )
        val scheduled = scheduler.schedule(plan, snapshot(observerEngaged = false))
        assertFalse(scheduled.commands.any { it.actor == SceneActorId.POLICE_DOG && it.clip == "LookAtCamera" })
        assertTrue(scheduled.commands.any { it.clip == "ReviewFile" })
    }

    @Test
    fun doorSoundRequiresDoorMotion() {
        val plan = RuntimeScenarioPlan(
            commands = listOf(
                SceneAnimationCommand(SceneActorId.POLICE_DOG, "ReviewFile", AnimationChannel.HANDS, loop = true)
            ),
            durationHintMs = 4_000L,
            sounds = listOf(
                SpatialSoundCommand(OfficeSoundId.DOOR_OPEN, OfficeZone.DOORWAY)
            )
        )
        val scheduled = scheduler.schedule(plan, snapshot(observerEngaged = false))
        assertFalse(scheduled.sounds.any { it.sound == OfficeSoundId.DOOR_OPEN })
    }

    @Test
    fun engagedHeadLookAddsEyesFirst() {
        val plan = RuntimeScenarioPlan(
            commands = listOf(
                SceneAnimationCommand(
                    actor = SceneActorId.POLICE_DOG,
                    clip = "LookAtCamera",
                    channel = AnimationChannel.HEAD,
                    delayMs = 200L
                )
            ),
            durationHintMs = 2_000L
        )
        val scheduled = scheduler.schedule(plan, snapshot(observerEngaged = true))
        val gaze = scheduled.commands.firstOrNull {
            it.actor == SceneActorId.POLICE_DOG &&
                it.clip == "LookAtCamera" &&
                it.channel == AnimationChannel.GAZE
        }
        val head = scheduled.commands.firstOrNull {
            it.actor == SceneActorId.POLICE_DOG &&
                it.clip == "LookAtCamera" &&
                it.channel == AnimationChannel.HEAD
        }
        assertTrue(gaze != null)
        assertTrue(head != null)
        assertTrue(gaze!!.delayMs < head!!.delayMs)
    }

    private fun snapshot(
        observerEngaged: Boolean,
        seatedActor: SceneActorId? = null
    ): OfficeWorldScheduler.Snapshot {
        val actors = Runtime3DAssetCatalog.actors.associate { asset ->
            asset.id to OfficeWorldScheduler.ActorRuntimeState(
                zone = asset.defaultZone,
                standing = asset.id != seatedActor,
                locomoting = false,
                currentClip = null
            )
        }
        return OfficeWorldScheduler.Snapshot(actors, observerEngaged)
    }
}
