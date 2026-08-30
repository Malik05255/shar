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

data class OfficeSceneState(
    val cue: OfficeCue = OfficeCue.NONE,
    val attention: DogAttention = DogAttention.CAMERA,
    val doorOpen: Boolean = false,
    val phoneRinging: Boolean = false,
    val staffVisible: Boolean = true,
    val staffAtDoor: Boolean = false,
    val staffSpeaking: Boolean = false,
    val staffLine: String = "",
    val revision: Long = 0L
)
