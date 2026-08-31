package com.malik.alshurti

import kotlin.random.Random

/**
 * Stateful local source of unbounded office life.
 *
 * Variation is not plain random sampling: recent dog tasks, staff beats and micro-events are kept
 * on separate cooldown queues so the viewer cannot observe the same semantic beat again a few
 * seconds later. At least every third beat contains one of the approved recorded physical sounds.
 */
class InfiniteOfficeScenarioGenerator(seed: Long = System.nanoTime()) {
    private var random = Random(seed)
    private val recentSignatures = ArrayDeque<String>()
    private val recentDogTasks = ArrayDeque<DogTask>()
    private val recentStaffEvents = ArrayDeque<StaffEvent>()
    private val recentMicroEvents = ArrayDeque<MicroEvent>()
    private var sequence = 0L
    private var beatsSinceApprovedSound = 0

    fun reset(seed: Long = System.nanoTime()) {
        random = Random(seed)
        recentSignatures.clear()
        recentDogTasks.clear()
        recentStaffEvents.clear()
        recentMicroEvents.clear()
        sequence = 0L
        beatsSinceApprovedSound = 0
    }

    fun next(observerEngaged: Boolean = false): RuntimeScenarioPlan {
        repeat(MAX_RETRIES) {
            val plan = compose(observerEngaged)
            val signature = signature(plan)
            if (signature !in recentSignatures) {
                rememberSignature(signature)
                return plan
            }
        }
        val fallback = compose(observerEngaged, forceVariant = sequence++)
        rememberSignature(signature(fallback))
        return fallback
    }

    private fun compose(observerEngaged: Boolean, forceVariant: Long? = null): RuntimeScenarioPlan {
        sequence += 1
        val variant = forceVariant ?: sequence
        val duration = random.nextLong(8_200L, 17_500L)

        val dogTask = chooseAvoiding(DogTask.entries.toList(), recentDogTasks)
        rememberRecent(recentDogTasks, dogTask, DOG_TASK_COOLDOWN)

        val staffEvent = chooseAvoiding(StaffEvent.entries.toList(), recentStaffEvents)
        rememberRecent(recentStaffEvents, staffEvent, STAFF_EVENT_COOLDOWN)

        val microEvent = chooseMicroEvent()
        rememberRecent(recentMicroEvents, microEvent, MICRO_EVENT_COOLDOWN)

        val commands = mutableListOf<SceneAnimationCommand>()
        val sounds = mutableListOf<SpatialSoundCommand>()

        commands += dogTask.commands(random, observerEngaged)
        commands += staffEvent.commands(random)
        sounds += staffEvent.sounds(random)

        val microDelay = random.nextLong(1_300L, (duration - 1_100L).coerceAtLeast(1_600L))
        commands += microEvent.commands(random, microDelay)
        sounds += microEvent.sounds(random, microDelay)

        // If a staff beat already contains one of our approved recordings, it also resets the
        // audible-life clock. Unsupported placeholders (keyboard/printer/footsteps) do not count.
        val hasApprovedSound = sounds.any { it.sound in approvedRecordedSounds }
        beatsSinceApprovedSound = if (hasApprovedSound) 0 else beatsSinceApprovedSound + 1

        // Natural asynchronous micro-motion: never synchronize the whole office on one beat.
        if (random.nextFloat() < 0.72f) {
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
            reason = "infinite-office-$variant-${dogTask.name.lowercase()}-${staffEvent.name.lowercase()}-${microEvent.name.lowercase()}",
            keepWorldRunning = true
        )
    }

    private fun chooseMicroEvent(): MicroEvent {
        val pool = if (beatsSinceApprovedSound >= MAX_SILENT_BEATS) {
            // Both choices have approved local OGG and a matching visible action.
            listOf(MicroEvent.DOOR, MicroEvent.PAGE)
        } else {
            // Weight useful physical events more heavily than silent decorative events.
            listOf(
                MicroEvent.DOOR,
                MicroEvent.DOOR,
                MicroEvent.PAGE,
                MicroEvent.PAGE,
                MicroEvent.GLANCE,
                MicroEvent.CUP,
                MicroEvent.CHAIR,
                MicroEvent.NONE
            )
        }
        return chooseAvoiding(pool, recentMicroEvents)
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
                    add(SceneAnimationCommand(SceneActorId.POLICE_DOG, hand, AnimationChannel.HANDS, loop = hand != "UsePhone", delayMs = random.nextLong(150L, 850L), playbackRate = randomRate(random, 0.88f, 1.04f)))
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
                val speaker = staffActors.random(random)
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
                val actor = staffActors.random(random)
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
            DESK_WORK -> emptyList()
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
            CUP -> listOf(SceneAnimationCommand(staffActors.random(random), "Drink", AnimationChannel.HANDS, delayMs = delay))
            CHAIR -> listOf(SceneAnimationCommand(SceneActorId.CHAIR, "Shift", AnimationChannel.PROP, delayMs = delay))
            PAGE -> listOf(SceneAnimationCommand(SceneActorId.POLICE_DOG, "TurnPage", AnimationChannel.HANDS, delayMs = delay))
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

    private fun signature(plan: RuntimeScenarioPlan): String {
        val animationSignature = plan.commands
            .map { "${it.actor}:${it.clip}:${it.channel}:${it.targetActor}" }
            .sorted()
            .joinToString("|")
        val soundSignature = plan.sounds
            .map { "${it.sound}:${it.zone}" }
            .sorted()
            .joinToString("|")
        return "$animationSignature#$soundSignature"
    }

    private fun rememberSignature(signature: String) {
        recentSignatures.addLast(signature)
        while (recentSignatures.size > RECENT_SIGNATURE_LIMIT) recentSignatures.removeFirst()
    }

    private fun <T> chooseAvoiding(pool: List<T>, recent: Collection<T>): T {
        val candidates = pool.filterNot { it in recent }.distinct()
        return (if (candidates.isNotEmpty()) candidates else pool.distinct()).random(random)
    }

    private fun <T> rememberRecent(queue: ArrayDeque<T>, value: T, maxSize: Int) {
        queue.addLast(value)
        while (queue.size > maxSize) queue.removeFirst()
    }

    private fun randomRate(min: Float, max: Float): Float = randomRate(random, min, max)

    companion object {
        private const val RECENT_SIGNATURE_LIMIT = 32
        private const val MAX_RETRIES = 20
        private const val DOG_TASK_COOLDOWN = 2
        private const val STAFF_EVENT_COOLDOWN = 3
        private const val MICRO_EVENT_COOLDOWN = 2
        private const val MAX_SILENT_BEATS = 1

        private val staffActors = listOf(
            SceneActorId.STAFF_MALE_01,
            SceneActorId.STAFF_MALE_02,
            SceneActorId.STAFF_FEMALE_01
        )
        private val approvedRecordedSounds = setOf(
            OfficeSoundId.PHONE_RING,
            OfficeSoundId.DOOR_OPEN,
            OfficeSoundId.DOOR_CLOSE,
            OfficeSoundId.PAGE_TURN,
            OfficeSoundId.PAPER_HANDLE
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
