package com.malik.alshurti

import kotlin.math.max
import kotlin.random.Random

/**
 * A persistent fictional security-office simulation.
 *
 * The observer is not the center of the scene. The office starts working immediately and continues
 * until speech is detected. Speech only interrupts the police dog's foreground task; background
 * staff may continue low-salience work so the room never freezes unnaturally.
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
    private val recentOpenings = ArrayDeque<Opening>()
    private var sequence = 0L

    fun reset(seed: Long = System.nanoTime()) {
        random = Random(seed)
        recentOpenings.clear()
        sequence = 0L
    }

    /** Different opening on every app session, while avoiding obvious immediate repeats. */
    fun openingPlan(): RuntimeScenarioPlan {
        val available = Opening.entries.filterNot { it in recentOpenings }.ifEmpty { Opening.entries }
        val opening = available[random.nextInt(available.size)]
        recentOpenings.addLast(opening)
        while (recentOpenings.size > 3) recentOpenings.removeFirst()
        return planFor(opening)
    }

    fun nextAmbientPlan(): RuntimeScenarioPlan {
        sequence += 1
        val candidates = listOf(
            ::paperworkBeat,
            ::staffDeskConversationBeat,
            ::staffWalkBeat,
            ::monitorAndNotesBeat,
            ::printerBeat,
            ::quietDoorTrafficBeat,
            ::backgroundPhoneBeat
        )
        return candidates[random.nextInt(candidates.size)]()
    }

    /** Called the instant voice activity starts, before transcription is complete. */
    fun onObserverSpeechStarted(): RuntimeScenarioPlan = RuntimeScenarioPlan(
        durationHintMs = 1_100L,
        reason = "speech-interruption",
        keepWorldRunning = true,
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "SetFileDown", AnimationChannel.HANDS, blendMs = 180),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtCamera", AnimationChannel.GAZE, blendMs = 120),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtCamera", AnimationChannel.HEAD, blendMs = 260, delayMs = 90),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "Listen", AnimationChannel.BODY, loop = true, blendMs = 360, delayMs = 180),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Type", AnimationChannel.HANDS, loop = true, playbackRate = 0.94f),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_01, "IdleDesk", AnimationChannel.BODY, loop = true)
        )
    )

    fun whileObserverIsTalking(): RuntimeScenarioPlan = RuntimeScenarioPlan(
        durationHintMs = 8_000L,
        reason = "listen-with-background-life",
        keepWorldRunning = true,
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "Listen", AnimationChannel.BODY, loop = true),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtCamera", AnimationChannel.GAZE, loop = true, blendMs = 120),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "Breathing", AnimationChannel.BODY, loop = true, playbackRate = 0.92f),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Type", AnimationChannel.HANDS, loop = true, playbackRate = 0.88f),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_02, "Read", AnimationChannel.HANDS, loop = true, playbackRate = 0.92f)
        )
    )

    fun returnToOfficeWork(): RuntimeScenarioPlan = RuntimeScenarioPlan(
        durationHintMs = 2_800L,
        reason = "return-to-work",
        keepWorldRunning = true,
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtDesk", AnimationChannel.GAZE, blendMs = 210),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtDesk", AnimationChannel.HEAD, blendMs = 330, delayMs = 120),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "ReachFile", AnimationChannel.HANDS, blendMs = 320, delayMs = 300),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "IdleWork", AnimationChannel.BODY, loop = true, blendMs = 480, delayMs = 620)
        ),
        sounds = listOf(
            SpatialSoundCommand(OfficeSoundId.PAPER_HANDLE, OfficeZone.POLICE_DESK, delayMs = 540, gain = 0.18f)
        )
    )

    private fun planFor(opening: Opening): RuntimeScenarioPlan = when (opening) {
        Opening.MORNING_PAPERWORK -> paperworkBeat()
        Opening.QUIET_DESK_CONVERSATION -> staffDeskConversationBeat()
        Opening.FILE_HANDOFF -> fileHandoffBeat()
        Opening.PRINTER_RUN -> printerBeat()
        Opening.SHIFT_CROSSING -> staffWalkBeat()
        Opening.PHONE_AT_BACKGROUND_DESK -> backgroundPhoneBeat()
    }

    private fun paperworkBeat() = RuntimeScenarioPlan(
        durationHintMs = random.nextLong(9_000L, 15_000L),
        reason = "normal-paperwork",
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "ReviewFile", AnimationChannel.HANDS, loop = true, playbackRate = randomRate(0.88f, 1.03f)),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtDesk", AnimationChannel.GAZE, loop = true),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "Breathing", AnimationChannel.BODY, loop = true, playbackRate = 0.93f),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Type", AnimationChannel.HANDS, loop = true, delayMs = 620),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_01, "Read", AnimationChannel.HANDS, loop = true, delayMs = 1_200)
        ),
        sounds = listOf(
            SpatialSoundCommand(OfficeSoundId.PAGE_TURN, OfficeZone.POLICE_DESK, delayMs = random.nextLong(1_500L, 3_400L), gain = 0.18f),
            SpatialSoundCommand(OfficeSoundId.KEYBOARD_SHORT, OfficeZone.RIGHT_WORKSTATION, delayMs = random.nextLong(2_200L, 5_200L), gain = 0.12f)
        )
    )

    private fun staffDeskConversationBeat() = RuntimeScenarioPlan(
        durationHintMs = random.nextLong(8_000L, 13_000L),
        reason = "background-colleague-conversation",
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "WriteNote", AnimationChannel.HANDS, loop = true, playbackRate = 0.94f),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtDesk", AnimationChannel.GAZE, loop = true),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "TalkToStaff", AnimationChannel.BODY, loop = true, targetActor = SceneActorId.STAFF_MALE_02),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_02, "ListenToStaff", AnimationChannel.BODY, loop = true, targetActor = SceneActorId.STAFF_FEMALE_01),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_02, "HeadNod", AnimationChannel.HEAD, delayMs = random.nextLong(1_500L, 3_500L))
        ),
        sounds = listOf(
            SpatialSoundCommand(
                OfficeSoundId.DISTANT_STAFF_SPEECH,
                OfficeZone.BACK_WORKSTATION,
                delayMs = 700L,
                gain = 0.10f,
                spokenLine = backgroundLine()
            )
        )
    )

    private fun fileHandoffBeat() = RuntimeScenarioPlan(
        durationHintMs = 10_500L,
        reason = "background-file-handoff",
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtMonitor", AnimationChannel.GAZE, loop = true),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_01, "WalkCarryFile", AnimationChannel.LOCOMOTION, targetActor = SceneActorId.STAFF_FEMALE_01),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "StandUp", AnimationChannel.BODY, delayMs = 2_000L),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_01, "CarryFile", AnimationChannel.HANDS, delayMs = 2_800L),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "SitDown", AnimationChannel.BODY, delayMs = 5_000L)
        ),
        sounds = listOf(
            SpatialSoundCommand(OfficeSoundId.FOOTSTEPS_SOFT, OfficeZone.CORRIDOR, gain = 0.15f),
            SpatialSoundCommand(OfficeSoundId.PAPER_HANDLE, OfficeZone.RIGHT_WORKSTATION, delayMs = 3_000L, gain = 0.13f),
            SpatialSoundCommand(OfficeSoundId.CHAIR_SHIFT, OfficeZone.RIGHT_WORKSTATION, delayMs = 5_100L, gain = 0.10f)
        )
    )

    private fun staffWalkBeat() = RuntimeScenarioPlan(
        durationHintMs = random.nextLong(7_500L, 11_500L),
        reason = "normal-corridor-traffic",
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "ReviewFile", AnimationChannel.HANDS, loop = true),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_02, "Walk", AnimationChannel.LOCOMOTION, targetActor = SceneActorId.PRINTER),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Type", AnimationChannel.HANDS, loop = true, delayMs = 400L)
        ),
        sounds = listOf(
            SpatialSoundCommand(OfficeSoundId.FOOTSTEPS_SOFT, OfficeZone.CORRIDOR, delayMs = 300L, gain = 0.16f)
        )
    )

    private fun monitorAndNotesBeat() = RuntimeScenarioPlan(
        durationHintMs = random.nextLong(8_000L, 12_000L),
        reason = "monitor-and-notes",
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtMonitor", AnimationChannel.GAZE, loop = true),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "WriteNote", AnimationChannel.HANDS, loop = true, delayMs = 1_000L),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_01, "Type", AnimationChannel.HANDS, loop = true),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Read", AnimationChannel.HANDS, loop = true)
        ),
        sounds = listOf(
            SpatialSoundCommand(OfficeSoundId.KEYBOARD_SHORT, OfficeZone.LEFT_WORKSTATION, delayMs = 1_800L, gain = 0.11f)
        )
    )

    private fun printerBeat() = RuntimeScenarioPlan(
        durationHintMs = 9_500L,
        reason = "printer-task",
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "IdleWork", AnimationChannel.BODY, loop = true),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_01, "Walk", AnimationChannel.LOCOMOTION, targetActor = SceneActorId.PRINTER),
            SceneAnimationCommand(SceneActorId.PRINTER, "Print", AnimationChannel.PROP, delayMs = 2_600L),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_01, "Read", AnimationChannel.HANDS, delayMs = 4_300L)
        ),
        sounds = listOf(
            SpatialSoundCommand(OfficeSoundId.FOOTSTEPS_SOFT, OfficeZone.CORRIDOR, gain = 0.12f),
            SpatialSoundCommand(OfficeSoundId.PRINTER_SHORT, OfficeZone.PRINTER_AREA, delayMs = 2_650L, gain = 0.14f)
        )
    )

    private fun quietDoorTrafficBeat() = RuntimeScenarioPlan(
        durationHintMs = 8_200L,
        reason = "quiet-door-traffic",
        commands = listOf(
            SceneAnimationCommand(SceneActorId.DOOR, "OpenDoor", AnimationChannel.PROP),
            SceneAnimationCommand(SceneActorId.STAFF_MALE_02, "Walk", AnimationChannel.LOCOMOTION, delayMs = 600L),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtDoor", AnimationChannel.GAZE, delayMs = 900L),
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtDesk", AnimationChannel.GAZE, delayMs = 2_300L),
            SceneAnimationCommand(SceneActorId.DOOR, "CloseDoor", AnimationChannel.PROP, delayMs = 3_100L)
        ),
        sounds = listOf(
            SpatialSoundCommand(OfficeSoundId.DOOR_OPEN, OfficeZone.DOORWAY, gain = 0.15f),
            SpatialSoundCommand(OfficeSoundId.FOOTSTEPS_SOFT, OfficeZone.DOORWAY, delayMs = 650L, gain = 0.13f),
            SpatialSoundCommand(OfficeSoundId.DOOR_CLOSE, OfficeZone.DOORWAY, delayMs = 3_150L, gain = 0.14f)
        )
    )

    private fun backgroundPhoneBeat() = RuntimeScenarioPlan(
        durationHintMs = 10_000L,
        reason = "background-phone-call",
        commands = listOf(
            SceneAnimationCommand(SceneActorId.POLICE_DOG, "ReviewFile", AnimationChannel.HANDS, loop = true),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "UsePhone", AnimationChannel.HANDS, delayMs = 1_100L),
            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "TalkToStaff", AnimationChannel.FACE, loop = true, delayMs = 1_700L)
        ),
        sounds = listOf(
            SpatialSoundCommand(OfficeSoundId.PHONE_RING, OfficeZone.RIGHT_WORKSTATION, gain = 0.12f),
            SpatialSoundCommand(OfficeSoundId.DISTANT_STAFF_SPEECH, OfficeZone.RIGHT_WORKSTATION, delayMs = 1_900L, gain = 0.08f, spokenLine = backgroundLine())
        )
    )

    private fun backgroundLine(): String = listOf(
        "تمام، وصلني.",
        "خله عندي وأنا أراجعه.",
        "إيه، حطه على المكتب.",
        "دقيقة وأرجع لك.",
        "تمام، واضح."
    ).random(random)

    private fun randomRate(min: Float, max: Float): Float =
        min + random.nextFloat() * max(0.01f, max - min)
}
