package com.malik.alshurti

enum class VoiceMode { ONLINE, OFFLINE }

enum class CallPhase { STARTING, LISTENING, THINKING, SPEAKING, ERROR }

enum class DogMood { CALM, LISTENING, THINKING, TALKING, SMILE, SERIOUS }

enum class MouthViseme {
    REST,
    OPEN,
    WIDE,
    ROUND,
    CLOSED
}

data class PoliceUiState(
    val mode: VoiceMode = VoiceMode.ONLINE,
    val phase: CallPhase = CallPhase.STARTING,
    val mood: DogMood = DogMood.CALM,
    val viseme: MouthViseme = MouthViseme.REST,
    val heardText: String = "",
    val replyText: String = "",
    val statusText: String = "جاري تجهيز الشرطي…",
    val errorMessage: String? = null,
    val firstGreetingDone: Boolean = false,
    val officeScene: OfficeSceneState = OfficeSceneState(),
    /** Full independent-actor choreography. The 3D renderer consumes this directly. */
    val runtimePlan: RuntimeScenarioPlan? = null,
    /** Monotonic plan identity so a new beat never requires rebuilding the whole 3D world. */
    val runtimePlanRevision: Long = 0L
)
