package com.malik.alshurti

import kotlin.random.Random

/**
 * Deterministic local source of unbounded office life.
 *
 * This does not render or generate media. It composes reusable actor actions, targets, timings and
 * physically sourced sounds into a very large state space. AI planners may replace a future beat,
 * but this generator guarantees that the office clock never waits for a network/model response.
 */
class InfiniteOfficeScenarioGenerator(seed: Long = System.nanoTime()) {
    private var random = Random(seed)
    private val recentSignatures = ArrayDeque<String>()
    private var sequence = 0L

    fun reset(seed: Long = System.nanoTime()) {
        random = Random(seed)
        recentSignatures.clear()
        sequence = 0L
    }

    fun next(observerEngaged: Boolean = false): RuntimeScenarioPlan {
        repeat(MAX_RETRIES) {
            val plan = compose(observerEngaged)
            val signature = signature(plan)
            if (signature !in recentSignatures) {
                remember(signature)
                return plan
            }
        }
        val fallback = compose(observerEngaged, forceVariant = sequence++)
        remember(signature(fallback))
        return fallback
    }

    private fun compose(observerEngaged: Boolean, forceVariant: Long? = null): RuntimeScenarioPlan {
        sequence += 1
        val variant = forceVariant ?: sequence
        val duration = random.nextLong(7_500L, 15_500L)
        val dogTask = dogTasks.random(random)
        val staffEvent = staffEvents.random(random)
        val microEvent = if (random.nextFloat() < 0.68f) microEvents.random(random) else MicroEvent.NONE

        val commands = mutableListOf<SceneAnimationCommand>()
        val sounds = mutableListOf<SpatialSoundCommand>()

        commands += dogTask.commands(random, observerEngaged)
        commands += staffEvent.commands(random)
        sounds += staffEvent.sounds(random)

        val microDelay = random.nextLong(1_100L, (duration - 1_000L).coerceAtLeast(1_300L))
        commands += microEvent.commands(random, microDelay)
        sounds += microEvent.sounds(random, microDelay)

        // Natural asynchronous micro-motion: never synchronize the whole office on one beat.
        if (random.nextBoolean()) {
            commands += SceneAnimationCommand(
                actor = staffActors.random(random),
                clip = listOf("Blink", "HeadNod", "GestureSmall").random(random),
                channel = listOf(AnimationChannel.FACE, AnimationChannel.HEAD, AnimationChannel.BODY).random(random),
                delayMs = random.nextLong(900L, duration - 500L),
                playbackRate = randomRate(0.90f, 1.07f)
            )
        }

        return RuntimeScenarioPlan(
            commands = commands,
            sounds = sounds,
            durationHintMs = duration,
            reason = "infinite-office-$variant-${dogTask.name.lowercase()}-${staffEvent.name.lowercase()}",
            keepWorldRunning = true
        )
    }

    private enum class DogTask {
        FILE_REVIEW,
        NOTES,
        MONITOR,
        QUIET_DESK,
        PHONE_CHECK;

        fun commands(random: Random, observerEngaged: Boolean): List<SceneAnimationCommand> {
            val gaze = if (observerEngaged) "LookAtCamera" else when (this) {
                FILE_REVIEW, NOTES -> "LookAtDesk"
                MONITOR -> "LookAtMonitor"
                PHONE_CHECK -> "LookAtDesk"
                QUIET_DESK -> listOf("LookAtDesk", "LookAtMonitor").random(random)
            }
            val hand = when (this) {
                FILE_REVIEW -> "ReviewFile"
                NOTES -> "WriteNote"
                MONITOR -> "IdleWork"
                QUIET_DESK -> "IdleWork"
                PHONE_CHECK -> "UsePhone"
            }
            return buildList {
                add(SceneAnimationCommand(SceneActorId.POLICE_DOG, "Breathing", AnimationChannel.BODY, loop = true, playbackRate = randomRate(random, 0.89f, 0.99f)))
                add(SceneAnimationCommand(SceneActorId.POLICE_DOG, gaze, AnimationChannel.GAZE, loop = true, blendMs = 170))
                if (!observerEngaged || this@DogTask == QUIET_DESK) {
                    add(SceneAnimationCommand(SceneActorId.POLICE_DOG, hand, AnimationChannel.HANDS, loop = hand !in setOf("UsePhone"), delayMs = random.nextLong(150L, 850L), playbackRate = randomRate(random, 0.88f, 1.04f)))
                }
            }
        }
    }

    private enum class StaffEvent {
        DESK_WORK,
        TWO_PERSON_TALK,
        FILE_HANDOFF,
        PRINTER_TRIP,
        CORRIDOR_CROSSING,
        BACKGROUND_PHONE,
        SHIFT_NOTE;

        fun commands(random: Random): List<SceneAnimationCommand> = when (this) {
            DESK_WORK -> listOf(
                SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Type", AnimationChannel.HANDS, loop = true, playbackRate = randomRate(random, 0.86f, 1.05f)),
                SceneAnimationCommand(SceneActorId.STAFF_MALE_01, listOf("Read", "Write").random(random), AnimationChannel.HANDS, loop = true, delayMs = random.nextLong(350L, 1_400L)),
                SceneAnimationCommand(SceneActorId.STAFF_MALE_02, "IdleDesk", AnimationChannel.BODY, loop = true)
            )
            TWO_PERSON_TALK -> {
                val speaker = listOf(SceneActorId.STAFF_FEMALE_01, SceneActorId.STAFF_MALE_01, SceneActorId.STAFF_MALE_02).random(random)
                val listener = staffActors.filterNot { it == speaker }.random(random)
                listOf(
                    SceneAnimationCommand(speaker, "TalkToStaff", AnimationChannel.BODY, loop = true, targetActor = listener),
                    SceneAnimationCommand(listener, "ListenToStaff", AnimationChannel.BODY, loop = true, targetActor = speaker, delayMs = 220L),
                    SceneAnimationCommand(listener, "HeadNod", AnimationChannel.HEAD, delayMs = random.nextLong(1_400L, 3_800L))
                )
            }
            FILE_HANDOFF -> {
                val carrier = listOf(SceneActorId.STAFF_MALE_01, SceneActorId.STAFF_MALE_02).random(random)
                val receiver = SceneActorId.STAFF_FEMALE_01
                listOf(
                    SceneAnimationCommand(carrier, "WalkCarryFile", AnimationChannel.LOCOMOTION, targetActor = receiver),
                    SceneAnimationCommand(receiver, "StandUp", AnimationChannel.BODY, delayMs = 1_700L),
                    SceneAnimationCommand(carrier, "CarryFile", AnimationChannel.HANDS, targetActor = receiver, delayMs = 2_500L),
                    SceneAnimationCommand(receiver, "SitDown", AnimationChannel.BODY, delayMs = 4_600L)
                )
            }
            PRINTER_TRIP -> {
                val actor = listOf(SceneActorId.STAFF_MALE_01, SceneActorId.STAFF_MALE_02, SceneActorId.STAFF_FEMALE_01).random(random)
                listOf(
                    SceneAnimationCommand(actor, "Walk", AnimationChannel.LOCOMOTION, targetActor = SceneActorId.PRINTER),
                    SceneAnimationCommand(SceneActorId.PRINTER, "Print", AnimationChannel.PROP, delayMs = 2_200L),
                    SceneAnimationCommand(actor, "Read", AnimationChannel.HANDS, delayMs = 3_500L)
                )
            }
            CORRIDOR_CROSSING -> {
                val actor = listOf(SceneActorId.STAFF_MALE_01, SceneActorId.STAFF_MALE_02, SceneActorId.VISITOR_01).random(random)
                listOf(
                    SceneAnimationCommand(actor, "Walk", AnimationChannel.LOCOMOTION, targetActor = listOf(SceneActorId.DOOR, SceneActorId.PRINTER).random(random)),
                    SceneAnimationCommand(staffActors.random(random), "Type", AnimationChannel.HANDS, loop = true, delayMs = random.nextLong(300L, 1_200L))
                )
            }
            BACKGROUND_PHONE -> {
                val actor = staffActors.random(random)
                listOf(
                    SceneAnimationCommand(actor, "UsePhone", AnimationChannel.HANDS, delayMs = 800L),
                    SceneAnimationCommand(actor, "TalkToStaff", AnimationChannel.FACE, loop = true, delayMs = 1_450L)
                )
            }
            SHIFT_NOTE -> {
                val writer = staffActors.random(random)
                val reader = staffActors.filterNot { it == writer }.random(random)
                listOf(
                    SceneAnimationCommand(writer, "Write", AnimationChannel.HANDS, loop = true),
                    SceneAnimationCommand(reader, "Read", AnimationChannel.HANDS, loop = true, delayMs = 700L),
                    SceneAnimationCommand(writer, "GestureSmall", AnimationChannel.BODY, delayMs = 2_300L, targetActor = reader)
                )
            }
        }

        fun sounds(random: Random): List<SpatialSoundCommand> = when (this) {
            DESK_WORK -> if (random.nextBoolean()) listOf(
                SpatialSoundCommand(OfficeSoundId.KEYBOARD_SHORT, OfficeZone.RIGHT_WORKSTATION, delayMs = random.nextLong(900L, 3_000L), gain = 0.09f)
            ) else emptyList()
            TWO_PERSON_TALK -> listOf(
                SpatialSoundCommand(OfficeSoundId.DISTANT_STAFF_SPEECH, OfficeZone.BACK_WORKSTATION, delayMs = 500L, gain = 0.08f, spokenLine = backgroundLines.random(random))
            )
            FILE_HANDOFF -> listOf(
                SpatialSoundCommand(OfficeSoundId.FOOTSTEPS_SOFT, OfficeZone.CORRIDOR, gain = 0.12f),
                SpatialSoundCommand(OfficeSoundId.PAPER_HANDLE, OfficeZone.RIGHT_WORKSTATION, delayMs = 2_550L, gain = 0.10f)
            )
            PRINTER_TRIP -> listOf(
                SpatialSoundCommand(OfficeSoundId.FOOTSTEPS_SOFT, OfficeZone.CORRIDOR, gain = 0.11f),
                SpatialSoundCommand(OfficeSoundId.PRINTER_SHORT, OfficeZone.PRINTER_AREA, delayMs = 2_250L, gain = 0.12f)
            )
            CORRIDOR_CROSSING -> listOf(
                SpatialSoundCommand(OfficeSoundId.FOOTSTEPS_SOFT, OfficeZone.CORRIDOR, gain = 0.11f)
            )
            BACKGROUND_PHONE -> listOf(
                SpatialSoundCommand(OfficeSoundId.PHONE_RING, OfficeZone.RIGHT_WORKSTATION, gain = 0.09f),
                SpatialSoundCommand(OfficeSoundId.DISTANT_STAFF_SPEECH, OfficeZone.RIGHT_WORKSTATION, delayMs = 1_600L, gain = 0.07f, spokenLine = backgroundLines.random(random))
            )
            SHIFT_NOTE -> emptyList()
        }
    }

    private enum class MicroEvent {
        NONE,
        DOOR,
        CUP,
        CHAIR,
        PAGE,
        GLANCE;

        fun commands(random: Random, delay: Long): List<SceneAnimationCommand> = when (this) {
            NONE -> emptyList()
            DOOR -> listOf(
                SceneAnimationCommand(SceneActorId.DOOR, "OpenDoor", AnimationChannel.PROP, delayMs = delay),
                SceneAnimationCommand(SceneActorId.DOOR, "CloseDoor", AnimationChannel.PROP, delayMs = delay + random.nextLong(1_300L, 2_900L))
            )
            CUP -> listOf(
                SceneAnimationCommand(staffActors.random(random), "Drink", AnimationChannel.HANDS, delayMs = delay)
            )
            CHAIR -> listOf(
                SceneAnimationCommand(SceneActorId.CHAIR, "Shift", AnimationChannel.PROP, delayMs = delay)
            )
            PAGE -> listOf(
                SceneAnimationCommand(SceneActorId.POLICE_DOG, "TurnPage", AnimationChannel.HANDS, delayMs = delay)
            )
            GLANCE -> listOf(
                SceneAnimationCommand(SceneActorId.POLICE_DOG, listOf("LookAtDoor", "LookAtStaff", "LookAtMonitor").random(random), AnimationChannel.GAZE, delayMs = delay, blendMs = 140)
            )
        }

        fun sounds(random: Random, delay: Long): List<SpatialSoundCommand> = when (this) {
            NONE, GLANCE -> emptyList()
            DOOR -> listOf(
                SpatialSoundCommand(OfficeSoundId.DOOR_OPEN, OfficeZone.DOORWAY, delayMs = delay, gain = 0.10f),
                SpatialSoundCommand(OfficeSoundId.DOOR_CLOSE, OfficeZone.DOORWAY, delayMs = delay + random.nextLong(1_300L, 2_900L), gain = 0.10f)
            )
            CUP -> listOf(SpatialSoundCommand(OfficeSoundId.CUP_SET_DOWN, OfficeZone.RIGHT_WORKSTATION, delayMs = delay + 1_000L, gain = 0.07f))
            CHAIR -> listOf(SpatialSoundCommand(OfficeSoundId.CHAIR_SHIFT, OfficeZone.BACK_WORKSTATION, delayMs = delay, gain = 0.07f))
            PAGE -> listOf(SpatialSoundCommand(OfficeSoundId.PAGE_TURN, OfficeZone.POLICE_DESK, delayMs = delay, gain = 0.08f))
        }
    }

    private fun signature(plan: RuntimeScenarioPlan): String = plan.commands
        .map { "${it.actor}:${it.clip}:${it.channel}:${it.targetActor}" }
        .sorted()
        .joinToString("|")

    private fun remember(signature: String) {
        recentSignatures.addLast(signature)
        while (recentSignatures.size > RECENT_SIGNATURE_LIMIT) recentSignatures.removeFirst()
    }

    private fun randomRate(min: Float, max: Float): Float = randomRate(random, min, max)

    companion object {
        private const val RECENT_SIGNATURE_LIMIT = 18
        private const val MAX_RETRIES = 16
        private val staffActors = listOf(
            SceneActorId.STAFF_MALE_01,
            SceneActorId.STAFF_MALE_02,
            SceneActorId.STAFF_FEMALE_01
        )
        private val backgroundLines = listOf(
            "تمام، وصلني.",
            "خله عندي وأنا أراجعه.",
            "حطه على المكتب لو سمحت.",
            "دقيقة وأرجع لك.",
            "تمام، واضح.",
            "أرسله لي وأنا أشيك عليه.",
            "خلصنا هذا، باقي الملف الثاني.",
            "خله عند الطابعة وبعدها آخذه."
        )

        private fun randomRate(random: Random, min: Float, max: Float): Float =
            min + random.nextFloat() * (max - min)
    }
}
