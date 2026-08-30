package com.malik.alshurti

enum class OfficeDoorState { CLOSED, OPENING, OPEN, CLOSING }
enum class OfficeActorMotion { IDLE, WALK_LEFT, WALK_RIGHT, DESK_WORK, TURN_TO_DOOR, ENTER, TALK, EXIT }
enum class DogLookTarget { CHILD, DOOR, OFFICER_A, OFFICER_B, DESK }
enum class SideSpeaker { NONE, OFFICER_A, OFFICER_B }
enum class OfficeSoundCue {
    NONE,
    DOOR_HANDLE,
    DOOR_OPEN,
    DOOR_CLOSE,
    KNOCK,
    PHONE_RING,
    FOOTSTEPS_LEFT_TO_RIGHT,
    FOOTSTEPS_RIGHT_TO_LEFT,
    KEYBOARD,
    PAPER,
    CHAIR,
    RADIO_BEEP
}

data class OfficeSceneState(
    val door: OfficeDoorState = OfficeDoorState.CLOSED,
    val officerA: OfficeActorMotion = OfficeActorMotion.DESK_WORK,
    val officerB: OfficeActorMotion = OfficeActorMotion.IDLE,
    val dogLookTarget: DogLookTarget = DogLookTarget.CHILD,
    val sideSpeaker: SideSpeaker = SideSpeaker.NONE,
    val scenarioLabel: String = "normal",
    val soundCue: OfficeSoundCue = OfficeSoundCue.NONE,
    val soundCueNonce: Long = 0L
)

data class OfficeDoorScenario(
    val speaker: SideSpeaker,
    val line: String,
    val officerMotion: OfficeActorMotion = OfficeActorMotion.ENTER
)

object OfficeScenarioLibrary {
    val doorScenarios: List<OfficeDoorScenario> = listOf(
        OfficeDoorScenario(SideSpeaker.OFFICER_A, "سيدي، التقرير اللي طلبته وصل."),
        OfficeDoorScenario(SideSpeaker.OFFICER_B, "معليش سيدي، فيه اتصال ينتظرك."),
        OfficeDoorScenario(SideSpeaker.OFFICER_A, "هذا الملف الجديد، أحطه عندك على المكتب؟"),
        OfficeDoorScenario(SideSpeaker.OFFICER_B, "سيدي، خلصنا الإجراء اللي قلت لنا عليه."),
        OfficeDoorScenario(SideSpeaker.OFFICER_A, "بس للتنبيه، الاجتماع بعد شوي."),
        OfficeDoorScenario(SideSpeaker.OFFICER_B, "تمام سيدي، كل شيء جاهز برا.")
    )
}
