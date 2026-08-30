package com.malik.alshurti

enum class OfficeCue {
    NONE,
    PHONE_RING,
    DOOR_OPEN,
    DOOR_CLOSE,
    STAFF_PASS,
    STAFF_SPEAK,
    PAPER_RUSTLE,
    FOOTSTEPS
}

enum class DogAttention {
    CAMERA,
    PHONE,
    DOOR,
    STAFF
}

/**
 * Full-body actions are intentionally separate from mood/attention. A dog can, for example,
 * WALK_TO_DOOR while staying attentive to the door, or TALK_STANDING while looking at CAMERA.
 * This is the contract consumed by either AI motion clips or a future rigged GLB.
 */
enum class DogAction {
    SEATED_IDLE,
    TALK_SEATED,
    STAND_UP,
    TALK_STANDING,
    WALK_AROUND_DESK,
    APPROACH_CAMERA,
    RETURN_FROM_CAMERA,
    WALK_TO_PHONE,
    ANSWER_PHONE,
    WALK_TO_DOOR,
    GREET_STAFF,
    RETURN_TO_DESK,
    REVIEW_FILE,
    SIT_DOWN
}

enum class CinematicScenario {
    NONE,
    STAND_AND_TALK,
    WALK_AND_CHECK_ROOM,
    PHONE_CALL,
    DOOR_VISITOR,
    APPROACH_CHILD
}

data class OfficeSceneState(
    val cue: OfficeCue = OfficeCue.NONE,
    val attention: DogAttention = DogAttention.CAMERA,
    val dogAction: DogAction = DogAction.SEATED_IDLE,
    val scenario: CinematicScenario = CinematicScenario.NONE,
    val doorOpen: Boolean = false,
    val phoneRinging: Boolean = false,
    val staffVisible: Boolean = true,
    val staffAtDoor: Boolean = false,
    val staffSpeaking: Boolean = false,
    val staffLine: String = "",
    val revision: Long = 0L
)
