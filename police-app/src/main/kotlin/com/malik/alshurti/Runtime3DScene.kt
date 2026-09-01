package com.malik.alshurti

/**
 * Persistent runtime 3D office contract.
 *
 * The office is a world, not a sequence of rendered scenes. Every object/person is an independent
 * GLB/PBR actor and keeps its own animation state. Scenario AI may only schedule commands for these
 * reusable actors; it never creates a new full-frame video.
 */
enum class SceneActorId {
    POLICE_DOG,
    OFFICE_SHELL,
    DESK,
    DOOR,
    PHONE,
    FILE,
    MONITOR,
    KEYBOARD,
    CHAIR,
    PRINTER,
    COFFEE_CUP,
    STAFF_MALE_01,
    STAFF_MALE_02,
    STAFF_FEMALE_01,
    VISITOR_01
}

enum class AnimationChannel {
    LOCOMOTION,
    BODY,
    HEAD,
    GAZE,
    FACE,
    HANDS,
    PROP
}

enum class OfficeZone {
    POLICE_DESK,
    LEFT_WORKSTATION,
    RIGHT_WORKSTATION,
    BACK_WORKSTATION,
    CORRIDOR,
    DOORWAY,
    PRINTER_AREA
}

enum class OfficeSoundId {
    FOOTSTEPS_SOFT,
    PAPER_HANDLE,
    PAGE_TURN,
    KEYBOARD_SHORT,
    CHAIR_SHIFT,
    DOOR_OPEN,
    DOOR_CLOSE,
    PHONE_RING,
    PRINTER_SHORT,
    DISTANT_STAFF_SPEECH,
    CUP_SET_DOWN
}

data class SceneActorAsset(
    val id: SceneActorId,
    val glbPath: String,
    val persistent: Boolean = true,
    val defaultZone: OfficeZone? = null
)

data class SceneAnimationCommand(
    val actor: SceneActorId,
    val clip: String,
    val channel: AnimationChannel,
    val loop: Boolean = false,
    val interruptible: Boolean = true,
    val blendMs: Int = 220,
    val targetActor: SceneActorId? = null,
    val delayMs: Long = 0L,
    val playbackRate: Float = 1f
)

data class SpatialSoundCommand(
    val sound: OfficeSoundId,
    val zone: OfficeZone,
    val delayMs: Long = 0L,
    val gain: Float = 0.35f,
    val duckWhenUserSpeaks: Boolean = true,
    val spokenLine: String? = null
)

data class RuntimeScenarioPlan(
    val commands: List<SceneAnimationCommand>,
    val durationHintMs: Long,
    val sounds: List<SpatialSoundCommand> = emptyList(),
    val reason: String = "",
    val keepWorldRunning: Boolean = true
)

/**
 * Canonical reusable actors. Missing optional actors are simply not instantiated; a scenario never
 * falls back to manufacturing a full-scene MP4.
 */
object Runtime3DAssetCatalog {
    val actors = listOf(
        SceneActorAsset(SceneActorId.POLICE_DOG, "models/police_dog.glb", defaultZone = OfficeZone.POLICE_DESK),
        SceneActorAsset(SceneActorId.OFFICE_SHELL, "models/office_shell.glb"),
        SceneActorAsset(SceneActorId.DESK, "models/desk.glb", defaultZone = OfficeZone.POLICE_DESK),
        SceneActorAsset(SceneActorId.DOOR, "models/door.glb", defaultZone = OfficeZone.DOORWAY),
        SceneActorAsset(SceneActorId.PHONE, "models/phone.glb", defaultZone = OfficeZone.POLICE_DESK),
        SceneActorAsset(SceneActorId.FILE, "models/file.glb", defaultZone = OfficeZone.POLICE_DESK),
        SceneActorAsset(SceneActorId.MONITOR, "models/monitor.glb", defaultZone = OfficeZone.POLICE_DESK),
        SceneActorAsset(SceneActorId.KEYBOARD, "models/keyboard.glb", defaultZone = OfficeZone.POLICE_DESK),
        SceneActorAsset(SceneActorId.CHAIR, "models/chair.glb", defaultZone = OfficeZone.POLICE_DESK),
        SceneActorAsset(SceneActorId.PRINTER, "models/printer.glb", defaultZone = OfficeZone.PRINTER_AREA),
        SceneActorAsset(SceneActorId.COFFEE_CUP, "models/coffee_cup.glb", defaultZone = OfficeZone.POLICE_DESK),
        SceneActorAsset(SceneActorId.STAFF_MALE_01, "models/staff_male_01.glb", defaultZone = OfficeZone.LEFT_WORKSTATION),
        SceneActorAsset(SceneActorId.STAFF_MALE_02, "models/staff_male_02.glb", defaultZone = OfficeZone.BACK_WORKSTATION),
        SceneActorAsset(SceneActorId.STAFF_FEMALE_01, "models/staff_female_01.glb", defaultZone = OfficeZone.RIGHT_WORKSTATION),
        SceneActorAsset(SceneActorId.VISITOR_01, "models/visitor_01.glb", persistent = false, defaultZone = OfficeZone.DOORWAY)
    )

    val dogCoreClips = setOf(
        "IdleWork", "Breathing", "Blink", "EyeSaccade",
        "LookAtDesk", "LookAtMonitor", "LookAtCamera", "LookAtDoor", "LookAtStaff",
        "ReachFile", "ReviewFile", "TurnPage", "WriteNote", "SetFileDown",
        "UsePhone", "Listen", "Talk", "StandUp", "SitDown", "Walk", "LeanBack",
        "VisemeRest", "VisemeOpen", "VisemeWide", "VisemeRound", "VisemeClosed"
    )

    val staffCoreClips = setOf(
        "IdleDesk", "Breathing", "Blink", "Type", "Read", "Write",
        "TalkToStaff", "ListenToStaff", "GestureSmall", "HeadNod",
        "Walk", "WalkCarryFile", "CarryFile", "StandUp", "SitDown",
        "UsePhone", "Drink", "OpenDoor", "CloseDoor"
    )

    val propClips = mapOf(
        SceneActorId.DOOR to setOf("OpenDoor", "CloseDoor", "Idle"),
        SceneActorId.PHONE to setOf("Ring", "Idle"),
        SceneActorId.FILE to setOf("Idle", "MoveToDesk", "MoveToHand"),
        SceneActorId.CHAIR to setOf("Idle", "Shift", "Turn"),
        SceneActorId.PRINTER to setOf("Idle", "Print"),
        SceneActorId.COFFEE_CUP to setOf("Idle", "MoveToHand", "MoveToDesk")
    )
}

/** Existing cinematic MP4s are migration-only fallbacks. */
object LegacyCinematicPolicy {
    const val ALLOW_NEW_FULL_SCENE_VIDEO = false
}
