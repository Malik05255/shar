package com.malik.alshurti

/**
 * Converts an AI plan into physically plausible runtime choreography.
 *
 * This is intentionally deterministic: generative planners may suggest intent, but they cannot
 * break motion continuity, teleport actors, synchronize everyone, or create sound with no source.
 */
class OfficeWorldScheduler {
    data class ActorRuntimeState(
        val zone: OfficeZone?,
        val standing: Boolean,
        val locomoting: Boolean,
        val currentClip: String? = null
    )

    data class Snapshot(
        val actors: Map<SceneActorId, ActorRuntimeState>,
        val observerEngaged: Boolean
    )

    fun schedule(plan: RuntimeScenarioPlan, snapshot: Snapshot): RuntimeScenarioPlan {
        val expanded = mutableListOf<SceneAnimationCommand>()
        var locomotionIndex = 0

        plan.commands.forEach { command ->
            val state = snapshot.actors[command.actor]
            var adjusted = command

            if (command.channel == AnimationChannel.LOCOMOTION) {
                // Never let background people begin walking on the same exact frame.
                val stagger = locomotionIndex * 430L
                locomotionIndex += 1
                adjusted = adjusted.copy(delayMs = maxOf(adjusted.delayMs, stagger))

                // A seated human/character must stand before walking.
                if (state?.standing == false && command.actor.isCharacter()) {
                    expanded += SceneAnimationCommand(
                        actor = command.actor,
                        clip = "StandUp",
                        channel = AnimationChannel.BODY,
                        delayMs = maxOf(0L, adjusted.delayMs - 650L),
                        blendMs = 260
                    )
                    adjusted = adjusted.copy(delayMs = maxOf(adjusted.delayMs, 650L))
                }
            }

            // Human attention reads eyes first, then head. A direct camera look is only legal after
            // the observer has actually spoken/engaged.
            if (command.actor == SceneActorId.POLICE_DOG && command.clip == "LookAtCamera") {
                if (!snapshot.observerEngaged) return@forEach
                if (command.channel == AnimationChannel.HEAD) {
                    expanded += command.copy(
                        channel = AnimationChannel.GAZE,
                        delayMs = command.delayMs,
                        blendMs = minOf(command.blendMs, 140)
                    )
                    adjusted = command.copy(delayMs = command.delayMs + 100L)
                }
            }

            // Hands cannot type/read/write while the same actor is already walking.
            if (state?.locomoting == true && command.channel == AnimationChannel.HANDS) {
                adjusted = adjusted.copy(delayMs = maxOf(adjusted.delayMs, 900L))
            }

            expanded += adjusted.copy(
                playbackRate = adjusted.playbackRate.coerceIn(0.82f, 1.12f),
                blendMs = adjusted.blendMs.coerceIn(90, 650)
            )
        }

        val allowedSounds = plan.sounds.filter { sound ->
            when (sound.sound) {
                OfficeSoundId.DOOR_OPEN,
                OfficeSoundId.DOOR_CLOSE -> expanded.any { it.actor == SceneActorId.DOOR }
                OfficeSoundId.PRINTER_SHORT -> expanded.any { it.actor == SceneActorId.PRINTER }
                OfficeSoundId.PHONE_RING -> expanded.any {
                    it.actor == SceneActorId.PHONE || it.clip == "UsePhone"
                }
                OfficeSoundId.PAPER_HANDLE,
                OfficeSoundId.PAGE_TURN -> expanded.any {
                    it.clip in setOf("ReviewFile", "TurnPage", "Read", "Write", "CarryFile", "WalkCarryFile")
                }
                OfficeSoundId.FOOTSTEPS_SOFT -> expanded.any { it.channel == AnimationChannel.LOCOMOTION }
                OfficeSoundId.CHAIR_SHIFT -> expanded.any { it.clip in setOf("StandUp", "SitDown") }
                OfficeSoundId.KEYBOARD_SHORT -> expanded.any { it.clip == "Type" }
                OfficeSoundId.DISTANT_STAFF_SPEECH -> expanded.any { it.clip == "TalkToStaff" }
                OfficeSoundId.CUP_SET_DOWN -> expanded.any { it.clip == "Drink" }
            }
        }.map { sound ->
            if (snapshot.observerEngaged) sound.copy(gain = minOf(sound.gain, 0.08f), duckWhenUserSpeaks = true)
            else sound
        }

        return plan.copy(commands = expanded, sounds = allowedSounds)
    }

    private fun SceneActorId.isCharacter(): Boolean = this in setOf(
        SceneActorId.POLICE_DOG,
        SceneActorId.STAFF_MALE_01,
        SceneActorId.STAFF_MALE_02,
        SceneActorId.STAFF_FEMALE_01,
        SceneActorId.VISITOR_01
    )
}
