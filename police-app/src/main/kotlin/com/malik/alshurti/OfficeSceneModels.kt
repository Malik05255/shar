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
    MONITOR,
    PAPER,
    PHONE,
    DOOR,
    STAFF
}

enum class BackgroundActivity {
    CALM_WORK,
    STAFF_WALK,
    DESK_CONVERSATION,
    PAPERWORK,
    DOOR_TRAFFIC
}

/**
 * Full-body actions are intentionally separate from mood/attention. The lightweight compositor
 * can keep the exact dog image independent from the office, while the legacy cinematic clips stay
 * available only as transitional motion until the cutout migration is complete.
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
    APPROACH_CHILD,
    AMBIENT_WORK
}

data class OfficeSceneState(
    val cue: OfficeCue = OfficeCue.NONE,
    val attention: DogAttention = DogAttention.PAPER,
    val dogAction: DogAction = DogAction.SEATED_IDLE,
    val scenario: CinematicScenario = CinematicScenario.AMBIENT_WORK,
    val backgroundActivity: BackgroundActivity = BackgroundActivity.CALM_WORK,
    val doorOpen: Boolean = false,
    val phoneRinging: Boolean = false,
    val staffVisible: Boolean = true,
    val staffAtDoor: Boolean = false,
    val staffSpeaking: Boolean = false,
    val staffLine: String = "",
    val revision: Long = 0L
)
