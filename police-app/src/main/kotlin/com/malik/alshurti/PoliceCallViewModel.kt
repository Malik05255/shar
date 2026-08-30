package com.malik.alshurti

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malik.alshurti.remote.RemotePoliceBrain
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PoliceCallViewModel(application: Application) : AndroidViewModel(application), PoliceVoiceEngine.Listener {
    private val preferences = application.getSharedPreferences(PREFS_NAME, 0)

    private val initialMode = runCatching {
        VoiceMode.valueOf(preferences.getString(KEY_MODE, VoiceMode.ONLINE.name) ?: VoiceMode.ONLINE.name)
    }.getOrDefault(VoiceMode.ONLINE)

    private val localBrain: PoliceBrain = QwenPoliceBrain(application.applicationContext)
    private val remoteBrain: PoliceBrain = RemotePoliceBrain(application.applicationContext)
    private val voiceEngine = PoliceVoiceEngine(application.applicationContext, this)

    private val _uiState = MutableStateFlow(
        PoliceUiState(
            mode = initialMode,
            statusText = "جاري فتح المكالمة…",
            readyToStart = false
        )
    )
    val uiState: StateFlow<PoliceUiState> = _uiState.asStateFlow()

    private val _officeSceneState = MutableStateFlow(OfficeSceneState())
    val officeSceneState: StateFlow<OfficeSceneState> = _officeSceneState.asStateFlow()

    private val scenarioVoice = OfficeScenarioVoice(
        context = application.applicationContext,
        listener = object : OfficeScenarioVoice.Listener {
            override fun onScenarioVoiceStarted() {
                _officeSceneState.update { state ->
                    state.copy(
                        officerA = if (state.sideSpeaker == SideSpeaker.OFFICER_A) OfficeActorMotion.TALK else state.officerA,
                        officerB = if (state.sideSpeaker == SideSpeaker.OFFICER_B) OfficeActorMotion.TALK else state.officerB
                    )
                }
            }

            override fun onScenarioVoiceFinished() {
                finishDoorScenario()
            }

            override fun onScenarioVoiceError(message: String) {
                // A background actor must never break the child's call.
                finishDoorScenario()
            }
        }
    )

    private var microphonePermissionGranted = false
    private var conversationLoopEnabled = false
    private var pendingConversationStart = false
    private var autoStartAttempted = false
    private var scenarioInProgress = false
    private var completedReplyTurns = 0
    private var lastScenarioTurn = -1
    private var scenarioCursor = 0
    private var backgroundBeat = 0
    private var soundCueNonce = 0L

    init {
        voiceEngine.setMode(initialMode)
        startBackgroundLife()
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        microphonePermissionGranted = granted
        if (!granted) {
            conversationLoopEnabled = false
            _uiState.update {
                it.copy(
                    phase = CallPhase.ERROR,
                    mood = DogMood.SERIOUS,
                    statusText = "اسمح باستخدام الميكروفون حتى يسمعك الشرطي.",
                    errorMessage = "إذن الميكروفون مطلوب.",
                    readyToStart = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                statusText = "جاري الاتصال بالشرطي…",
                errorMessage = null,
                readyToStart = false
            )
        }

        if (!autoStartAttempted) {
            autoStartAttempted = true
            startConversation()
        }
    }

    fun chooseMode(mode: VoiceMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        conversationLoopEnabled = false
        pendingConversationStart = false
        scenarioInProgress = false
        scenarioVoice.interrupt()
        resetOfficeScene()
        voiceEngine.setMode(mode)

        _uiState.update {
            it.copy(
                mode = mode,
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                heardText = "",
                replyText = "",
                statusText = if (mode == VoiceMode.ONLINE) {
                    "وضع الإنترنت — جاري الاتصال بالصوت الحقيقي…"
                } else {
                    "بدون إنترنت — جاري فتح المكالمة المحلية…"
                },
                errorMessage = null,
                readyToStart = false
            )
        }

        if (microphonePermissionGranted) startConversation()
    }

    fun startConversation() {
        if (!microphonePermissionGranted) return

        conversationLoopEnabled = false
        pendingConversationStart = true
        scenarioInProgress = false
        scenarioVoice.interrupt()
        resetOfficeScene()
        voiceEngine.stopListening()
        voiceEngine.interruptSpeech()

        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                heardText = "",
                statusText = if (it.mode == VoiceMode.ONLINE) {
                    "جاري الاتصال بمكتب الشرطي…"
                } else {
                    "جاري فتح المكالمة المحلية…"
                },
                errorMessage = null,
                readyToStart = false
            )
        }

        if (_uiState.value.mode == VoiceMode.OFFLINE) {
            localBrain.prepare(allowDownload = false)
        }
        voiceEngine.prepareVoice()
    }

    fun retryListening() {
        if (!microphonePermissionGranted || scenarioInProgress) return
        conversationLoopEnabled = true
        _officeSceneState.update {
            it.copy(
                dogLookTarget = DogLookTarget.CHILD,
                sideSpeaker = SideSpeaker.NONE
            )
        }
        _uiState.update {
            it.copy(
                phase = CallPhase.LISTENING,
                mood = DogMood.LISTENING,
                viseme = MouthViseme.REST,
                statusText = "تكلم… أنا أسمعك",
                errorMessage = null,
                readyToStart = false
            )
        }
        voiceEngine.startListening()
    }

    fun interruptAndListen() {
        pendingConversationStart = false
        scenarioInProgress = false
        scenarioVoice.interrupt()
        resetOfficeScene()
        conversationLoopEnabled = true
        voiceEngine.interruptSpeech()
        retryListening()
    }

    private fun activeBrain(): PoliceBrain =
        if (_uiState.value.mode == VoiceMode.ONLINE) remoteBrain else localBrain

    private fun handleRecognizedText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            retryListening()
            return
        }

        _uiState.update {
            it.copy(
                heardText = clean,
                phase = CallPhase.THINKING,
                mood = DogMood.THINKING,
                viseme = MouthViseme.REST,
                statusText = "الشرطي يفكر…",
                errorMessage = null,
                readyToStart = false
            )
        }

        viewModelScope.launch {
            runCatching { activeBrain().reply(clean) }
                .onSuccess { reply ->
                    completedReplyTurns += 1
                    _uiState.update {
                        it.copy(
                            replyText = reply.text,
                            phase = CallPhase.SPEAKING,
                            mood = reply.mood,
                            statusText = "الشرطي يرد عليك…",
                            errorMessage = null
                        )
                    }
                    voiceEngine.speak(reply.text)
                }
                .onFailure { error ->
                    conversationLoopEnabled = false
                    _uiState.update {
                        it.copy(
                            phase = CallPhase.ERROR,
                            mood = DogMood.SERIOUS,
                            viseme = MouthViseme.REST,
                            statusText = if (it.mode == VoiceMode.ONLINE) {
                                "تعذر الاتصال بمكتب الشرطي. تأكد أن خادم الصوت شغال على نفس الشبكة ثم أعد المحاولة."
                            } else {
                                "تعذر تجهيز الرد المحلي. أعد المحاولة."
                            },
                            errorMessage = error.message ?: "تعذر تجهيز الرد.",
                            readyToStart = false
                        )
                    }
                }
        }
    }

    private fun startBackgroundLife() {
        viewModelScope.launch {
            while (isActive) {
                delay(BACKGROUND_BEAT_MS)
                if (scenarioInProgress || _uiState.value.phase == CallPhase.ERROR) continue

                backgroundBeat += 1
                val phase = _uiState.value.phase
                _officeSceneState.update { current ->
                    when (backgroundBeat % 4) {
                        0 -> current.copy(officerA = OfficeActorMotion.DESK_WORK, officerB = OfficeActorMotion.WALK_LEFT)
                        1 -> current.copy(officerA = OfficeActorMotion.WALK_RIGHT, officerB = OfficeActorMotion.IDLE)
                        2 -> current.copy(officerA = OfficeActorMotion.DESK_WORK, officerB = OfficeActorMotion.WALK_RIGHT)
                        else -> current.copy(officerA = OfficeActorMotion.IDLE, officerB = OfficeActorMotion.DESK_WORK)
                    }
                }

                // Never play background Foley while the child's microphone is actively listening.
                if (phase == CallPhase.LISTENING) continue

                when (backgroundBeat % 8) {
                    0 -> emitCue(OfficeSoundCue.KEYBOARD)
                    1 -> emitCue(OfficeSoundCue.PAPER)
                    2 -> emitCue(OfficeSoundCue.FOOTSTEPS_RIGHT_TO_LEFT)
                    3 -> if (phase == CallPhase.THINKING || phase == CallPhase.SPEAKING) emitCue(OfficeSoundCue.PHONE_RING)
                    4 -> emitCue(OfficeSoundCue.CHAIR)
                    5 -> emitCue(OfficeSoundCue.KEYBOARD)
                    6 -> emitCue(OfficeSoundCue.FOOTSTEPS_LEFT_TO_RIGHT)
                    else -> if (phase != CallPhase.STARTING) emitCue(OfficeSoundCue.RADIO_BEEP)
                }
            }
        }
    }

    private fun shouldRunDoorScenario(): Boolean =
        _uiState.value.mode == VoiceMode.ONLINE &&
            !scenarioInProgress &&
            completedReplyTurns >= SCENARIO_FIRST_TURN &&
            completedReplyTurns % SCENARIO_EVERY_TURNS == 0 &&
            lastScenarioTurn != completedReplyTurns

    private fun startDoorScenario(): Boolean {
        if (!shouldRunDoorScenario()) return false

        val scenarios = OfficeScenarioLibrary.doorScenarios
        if (scenarios.isEmpty()) return false
        val scenario = scenarios[scenarioCursor % scenarios.size]
        scenarioCursor += 1
        lastScenarioTurn = completedReplyTurns
        scenarioInProgress = true
        conversationLoopEnabled = false
        voiceEngine.stopListening()

        viewModelScope.launch {
            // Knock first, then the handle and hinge. Every cue is spatialized from the door side.
            emitCue(OfficeSoundCue.KNOCK)
            delay(520)
            emitCue(OfficeSoundCue.DOOR_HANDLE)
            delay(180)

            _officeSceneState.update {
                it.copy(
                    door = OfficeDoorState.OPENING,
                    dogLookTarget = DogLookTarget.DOOR,
                    sideSpeaker = SideSpeaker.NONE,
                    scenarioLabel = "door-opening"
                )
            }
            emitCue(OfficeSoundCue.DOOR_OPEN)
            delay(720)

            _officeSceneState.update { current ->
                current.copy(
                    door = OfficeDoorState.OPEN,
                    officerA = if (scenario.speaker == SideSpeaker.OFFICER_A) scenario.officerMotion else current.officerA,
                    officerB = if (scenario.speaker == SideSpeaker.OFFICER_B) scenario.officerMotion else current.officerB,
                    dogLookTarget = if (scenario.speaker == SideSpeaker.OFFICER_A) DogLookTarget.OFFICER_A else DogLookTarget.OFFICER_B,
                    sideSpeaker = scenario.speaker,
                    scenarioLabel = "officer-entering"
                )
            }
            emitCue(
                if (scenario.speaker == SideSpeaker.OFFICER_A) {
                    OfficeSoundCue.FOOTSTEPS_LEFT_TO_RIGHT
                } else {
                    OfficeSoundCue.FOOTSTEPS_RIGHT_TO_LEFT
                }
            )
            delay(760)

            _officeSceneState.update { current ->
                current.copy(
                    officerA = if (scenario.speaker == SideSpeaker.OFFICER_A) OfficeActorMotion.TALK else current.officerA,
                    officerB = if (scenario.speaker == SideSpeaker.OFFICER_B) OfficeActorMotion.TALK else current.officerB,
                    scenarioLabel = "officer-speaking"
                )
            }
            scenarioVoice.speak(scenario.line, scenario.speaker)
        }
        return true
    }

    private fun finishDoorScenario() {
        if (!scenarioInProgress) return
        viewModelScope.launch {
            val speaker = _officeSceneState.value.sideSpeaker
            _officeSceneState.update { current ->
                current.copy(
                    officerA = if (speaker == SideSpeaker.OFFICER_A) OfficeActorMotion.EXIT else current.officerA,
                    officerB = if (speaker == SideSpeaker.OFFICER_B) OfficeActorMotion.EXIT else current.officerB,
                    sideSpeaker = SideSpeaker.NONE,
                    scenarioLabel = "officer-exiting"
                )
            }
            emitCue(
                if (speaker == SideSpeaker.OFFICER_A) {
                    OfficeSoundCue.FOOTSTEPS_RIGHT_TO_LEFT
                } else {
                    OfficeSoundCue.FOOTSTEPS_LEFT_TO_RIGHT
                }
            )
            delay(720)

            _officeSceneState.update { current ->
                current.copy(
                    door = OfficeDoorState.CLOSING,
                    dogLookTarget = DogLookTarget.DOOR,
                    scenarioLabel = "door-closing"
                )
            }
            emitCue(OfficeSoundCue.DOOR_CLOSE)
            delay(620)

            scenarioInProgress = false
            resetOfficeScene()
            conversationLoopEnabled = true
            delay(140)
            retryListening()
        }
    }

    private fun emitCue(cue: OfficeSoundCue) {
        soundCueNonce += 1L
        _officeSceneState.update {
            it.copy(
                soundCue = cue,
                soundCueNonce = soundCueNonce
            )
        }
    }

    private fun resetOfficeScene() {
        _officeSceneState.value = OfficeSceneState(
            officerA = OfficeActorMotion.DESK_WORK,
            officerB = OfficeActorMotion.IDLE,
            dogLookTarget = DogLookTarget.CHILD,
            soundCueNonce = soundCueNonce
        )
    }

    override fun onSpeechPreparing(percent: Int, message: String) {
        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = if (percent in 1..99) "جاري تجهيز الاستماع لأول مرة… $percent%" else "جاري تجهيز الاستماع…",
                errorMessage = null,
                readyToStart = false
            )
        }
    }

    override fun onReadyToListen() {
        _uiState.update {
            it.copy(
                phase = CallPhase.LISTENING,
                mood = DogMood.LISTENING,
                viseme = MouthViseme.REST,
                statusText = "تكلم… أنا أسمعك",
                errorMessage = null,
                readyToStart = false
            )
        }
    }

    override fun onSpeechStarted() {
        _uiState.update {
            it.copy(
                phase = CallPhase.LISTENING,
                mood = DogMood.LISTENING,
                statusText = "أسمعك…"
            )
        }
    }

    override fun onPartialText(text: String) {
        _uiState.update { it.copy(heardText = text) }
    }

    override fun onFinalText(text: String) = handleRecognizedText(text)

    override fun onSpeechError(message: String, recoverable: Boolean) {
        if (recoverable && microphonePermissionGranted && conversationLoopEnabled && !scenarioInProgress) {
            _uiState.update {
                it.copy(
                    phase = CallPhase.LISTENING,
                    mood = DogMood.LISTENING,
                    viseme = MouthViseme.REST,
                    statusText = "ما سمعتك بوضوح… تكلم مرة ثانية.",
                    errorMessage = null
                )
            }
            viewModelScope.launch {
                delay(350)
                retryListening()
            }
        } else if (!scenarioInProgress) {
            conversationLoopEnabled = false
            _uiState.update {
                it.copy(
                    phase = CallPhase.ERROR,
                    mood = DogMood.SERIOUS,
                    viseme = MouthViseme.REST,
                    statusText = "تعذر تشغيل الاستماع. اضغط إعادة المحاولة.",
                    errorMessage = message,
                    readyToStart = false
                )
            }
        }
    }

    override fun onTtsPreparing(percent: Int, message: String) {
        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = if (it.mode == VoiceMode.ONLINE) "جاري الاتصال بصوت الشرطي الحقيقي…" else "جاري تجهيز صوت الشرطي…",
                errorMessage = null,
                readyToStart = false
            )
        }
    }

    override fun onTtsReady() {
        if (!pendingConversationStart) return

        pendingConversationStart = false
        conversationLoopEnabled = true
        val greeting = "هلا، معك الشرطي. وش عندك؟"
        _uiState.update {
            it.copy(
                phase = CallPhase.SPEAKING,
                mood = DogMood.SMILE,
                replyText = greeting,
                statusText = "الشرطي يتكلم…",
                firstGreetingDone = true,
                readyToStart = false
            )
        }
        voiceEngine.speak(greeting)
    }

    override fun onTtsStarted() {
        _uiState.update {
            it.copy(
                phase = CallPhase.SPEAKING,
                mood = if (it.mood == DogMood.SMILE || it.mood == DogMood.SERIOUS) it.mood else DogMood.TALKING,
                statusText = "الشرطي يتكلم…",
                readyToStart = false
            )
        }
    }

    override fun onViseme(viseme: MouthViseme) {
        _uiState.update { it.copy(viseme = viseme) }
    }

    override fun onTtsFinished() {
        _uiState.update { it.copy(viseme = MouthViseme.REST) }
        if (conversationLoopEnabled && microphonePermissionGranted) {
            if (startDoorScenario()) return
            viewModelScope.launch {
                delay(110)
                retryListening()
            }
        }
    }

    override fun onTtsError(message: String) {
        pendingConversationStart = false
        conversationLoopEnabled = false
        _uiState.update {
            it.copy(
                phase = CallPhase.ERROR,
                mood = DogMood.SERIOUS,
                viseme = MouthViseme.REST,
                statusText = if (it.mode == VoiceMode.ONLINE) {
                    "تعذر الاتصال بصوت الشرطي الحقيقي. تأكد أن الخادم شغال على نفس الشبكة ثم أعد المحاولة."
                } else {
                    "تعذر تشغيل الصوت المحلي. أعد المحاولة."
                },
                errorMessage = message,
                readyToStart = false
            )
        }
    }

    override fun onCleared() {
        scenarioVoice.release()
        localBrain.release()
        remoteBrain.release()
        voiceEngine.release()
        super.onCleared()
    }

    private companion object {
        const val PREFS_NAME = "alshurti_voice_settings"
        const val KEY_MODE = "voice_mode"
        const val BACKGROUND_BEAT_MS = 7_500L
        const val SCENARIO_FIRST_TURN = 3
        const val SCENARIO_EVERY_TURNS = 3
    }
}
