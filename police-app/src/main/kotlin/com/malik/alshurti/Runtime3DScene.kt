package com.malik.alshurti

/**
 * Runtime 3D scene contract.
 *
 * Product rule: every visible object is an independent GLB/PBR actor. Scenario generation may only
 * choose commands for these actors; it must never request or bundle a new full-scene video.
 */
enum class SceneActorId {
    POLICE_DOG,
    OFFICE_SHELL,
    DESK,
    DOOR,
    PHONE,
    FILE,
    STAFF_MALE_01,
    STAFF_FEMALE_01
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

data class SceneActorAsset(
    val id: SceneActorId,
    val glbPath: String,
    val persistent: Boolean = true
)

data class SceneAnimationCommand(
    val actor: SceneActorId,
    val clip: String,
    val channel: AnimationChannel,
    val loop: Boolean = false,
    val interruptible: Boolean = true,
    val blendMs: Int = 180,
    val targetActor: SceneActorId? = null
)

data class RuntimeScenarioPlan(
    val commands: List<SceneAnimationCommand>,
    val durationHintMs: Long,
    val reason: String = ""
)

/**
 * One canonical asset per object. Reuse these actors across unlimited scenarios.
 * Animation clips live inside the relevant GLB or in small reusable animation assets.
 */
object Runtime3DAssetCatalog {
    val actors = listOf(
        SceneActorAsset(SceneActorId.POLICE_DOG, "models/police_dog.glb"),
        SceneActorAsset(SceneActorId.OFFICE_SHELL, "models/office_shell.glb"),
        SceneActorAsset(SceneActorId.DESK, "models/desk.glb"),
        SceneActorAsset(SceneActorId.DOOR, "models/door.glb"),
        SceneActorAsset(SceneActorId.PHONE, "models/phone.glb"),
        SceneActorAsset(SceneActorId.FILE, "models/file.glb"),
        SceneActorAsset(SceneActorId.STAFF_MALE_01, "models/staff_male_01.glb"),
        SceneActorAsset(SceneActorId.STAFF_FEMALE_01, "models/staff_female_01.glb")
    )

    val dogCoreClips = setOf(
        "IdleWork",
        "Breathing",
        "Blink",
        "LookAtDesk",
        "LookAtCamera",
        "LookAtDoor",
        "LookAtStaff",
        "ReviewFile",
        "UsePhone",
        "Listen",
        "Talk",
        "StandUp",
        "SitDown",
        "Walk"
    )

    val staffCoreClips = setOf(
        "IdleDesk",
        "Type",
        "Read",
        "TalkToStaff",
        "Walk",
        "CarryFile",
        "OpenDoor",
        "CloseDoor"
    )
}

/**
 * Existing MP4 clips are migration-only fallbacks. No new scenario is allowed to add another
 * full-frame cinematic MP4. Once the independent 3D actors are available, this legacy path is
 * removed entirely.
 */
object LegacyCinematicPolicy {
    const val ALLOW_NEW_FULL_SCENE_VIDEO = false
}
