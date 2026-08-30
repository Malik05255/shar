package com.malik.alshurti

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malik.alshurti.voice.SaudiHumanVoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PoliceCallViewModel(application: Application) : AndroidViewModel(application), PoliceVoiceEngine.Listener {
    private val preferences = application.getSharedPreferences(PREFS_NAME, 0)
    private val initialMode = VoiceMode.ONLINE

    private val brain: PoliceBrain = LocalPoliceBrain()
    private val voiceEngine = PoliceVoiceEngine(application.applicationContext, this)
    private val officeSoundscape = OfficeSoundscape(application.applicationContext)

    private val _uiState = MutableStateFlow(PoliceUiState(mode = initialMode))
    val uiState: StateFlow<PoliceUiState> = _uiState.asStateFlow()

    private var microphonePermissionGranted = false
    private var ttsReady = false
    private var staffVoiceReady = false
    private var sessionStarted = false
    private var completedPoliceTurns = 0
    private var staffScenarioActive = false
    private var officeEventJob: Job? = null

    private val staffVoice = SaudiHumanVoice(
        context = application.applicationContext,
        role = SaudiHumanVoice.VoiceRole.STAFF,
        callbacks = object : SaudiHumanVoice.Callbacks {
            override fun onPreparing(percent: Int, message: String) = Unit

            override fun onReady() {
                staffVoiceReady = true
            }

            override fun onSpeechStarted(durationMs: Long) {
                if (!staffScenarioActive) return
                _uiState.update {
                    it.copy(
                        phase = CallPhase.THINKING,
                        mood = DogMood.LISTENING,
                        officeScene = it.officeScene.copy(
                            cue = OfficeCue.STAFF_SPEAK,
                            attention = DogAttention.STAFF,
                            staffSpeaking = true,
                            revision = it.officeScene.revision + 1
                        )
                    )
                }
                officeSoundscape.setConversationPhase(CallPhase.THINKING)
            }

            override fun onSpeechCursor(fraction: Float) = Unit

            override fun onSpeechFinished() {
                if (staffScenarioActive) closeDoorThenListen()
            }

            override fun onError(message: String) {
                staffVoiceReady = false
                if (staffScenarioActive) closeDoorThenListen()
            }
        }
    )

    init {
        preferences.edit().putString(KEY_MODE, VoiceMode.ONLINE.name).apply()
        officeSoundscape.start()
        officeSoundscape.setConversationPhase(CallPhase.STARTING)
        staffVoice.prepare()
        voiceEngine.setMode(initialMode)
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        microphonePermissionGranted = granted
        if (!granted) {
            setPhase(CallPhase.ERROR)
            _uiState.update {
                it.copy(
                    mood = DogMood.SERIOUS,
                    viseme = MouthViseme.REST,
                    statusText = "اسمح للشرطي باستخدام الميكروفون حتى يسمعك.",
                    errorMessage = "إذن الميكروفون مطلوب للمحادثة الصوتية."
                )
            }
            return
        }
        tryStartSession()
    }

    fun chooseMode(mode: VoiceMode) {
        if (mode != VoiceMode.ONLINE) {
            setPhase(CallPhase.ERROR)
            _uiState.update {
                it.copy(
                    mode = VoiceMode.ONLINE,
                    mood = DogMood.SERIOUS,
                    viseme = MouthViseme.REST,
                    statusText = "الصوت السعودي الطبيعي يعمل عبر الإنترنت فقط.",
                    errorMessage = "تم تعطيل الصوت المحلي لأنه لا يحقق الجودة المطلوبة."
                )
            }
            return
        }

        cancelOfficeEvent()
        preferences.edit().putString(KEY_MODE, VoiceMode.ONLINE.name).apply()
        ttsReady = false
        sessionStarted = false
        voiceEngine.setMode(VoiceMode.ONLINE)
        setPhase(CallPhase.STARTING)
        _uiState.update {
            it.copy(
                mode = VoiceMode.ONLINE,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = "جاري تجهيز الصوت السعودي الطبيعي…",
                errorMessage = null,
                officeScene = OfficeSceneState()
            )
        }
    }

    fun retryListening() {
        if (!microphonePermissionGranted) return
        staffScenarioActive = false
        staffVoice.interrupt()
        officeEventJob = null
        resetOfficeScene()
        setPhase(CallPhase.LISTENING)
        _uiState.update {
            it.copy(
                mood = DogMood.LISTENING,
                viseme = MouthViseme.REST,
                statusText = "تكلم… أنا أسمعك",
                errorMessage = null
            )
        }
        voiceEngine.startListening()
    }

    fun interruptAndListen() {
        voiceEngine.interruptSpeech()
        staffVoice.interrupt()
        cancelOfficeEvent()
        retryListening()
    }

    private fun tryStartSession() {
        if (!microphonePermissionGranted || !ttsReady || sessionStarted) return
        sessionStarted = true

        if (!_uiState.value.firstGreetingDone) {
            val greeting = "هلا يا بطل، معك الشرطي. وش عندك؟"
            setPhase(CallPhase.SPEAKING)
            _uiState.update {
                it.copy(
                    mood = DogMood.SMILE,
                    replyText = greeting,
                    statusText = "الشرطي يتكلم…",
                    firstGreetingDone = true
                )
            }
            voiceEngine.speak(greeting)
        } else {
            retryListening()
        }
    }

    private fun handleRecognizedText(text: String) {
        if (text.isBlank()) {
            retryListening()
            return
        }

        staffScenarioActive = false
        staffVoice.interrupt()
        cancelOfficeEvent()
        setPhase(CallPhase.THINKING)
        _uiState.update {
            it.copy(
                heardText = text,
                mood = DogMood.THINKING,
                viseme = MouthViseme.REST,
                statusText = "لحظة… أفكر في كلامك",
                errorMessage = null,
                officeScene = OfficeSceneState()
            )
        }

        viewModelScope.launch {
            runCatching { brain.reply(text) }
                .onSuccess { reply ->
                    setPhase(CallPhase.SPEAKING)
                    _uiState.update {
                        it.copy(
                            replyText = reply.text,
                            mood = reply.mood,
                            statusText = "الشرطي يرد عليك…"
                        )
                    }
                    voiceEngine.speak(reply.text)
                }
                .onFailure { error ->
                    setPhase(CallPhase.ERROR)
                    _uiState.update {
                        it.copy(
                            mood = DogMood.SERIOUS,
                            viseme = MouthViseme.REST,
                            statusText = "صار خطأ بسيط، حاول مرة ثانية.",
                            errorMessage = error.message
                        )
                    }
                }
        }
    }

    override fun onReadyToListen() {
        setPhase(CallPhase.LISTENING)
        _uiState.update {
            it.copy(
                mood = DogMood.LISTENING,
                viseme = MouthViseme.REST,
                statusText = "تكلم… أنا أسمعك",
                errorMessage = null
            )
        }
    }

    override fun onSpeechStarted() {
        staffScenarioActive = false
        staffVoice.interrupt()
        cancelOfficeEvent()
        setPhase(CallPhase.LISTENING)
        _uiState.update {
            it.copy(
                mood = DogMood.LISTENING,
                viseme = MouthViseme.REST,
                statusText = "أسمعك…"
            )
        }
    }

    override fun onPartialText(text: String) {
        _uiState.update { it.copy(heardText = text) }
    }

    override fun onFinalText(text: String) = handleRecognizedText(text)

    override fun onSpeechError(message: String, recoverable: Boolean) {
        if (recoverable && microphonePermissionGranted) {
            setPhase(CallPhase.LISTENING)
            _uiState.update {
                it.copy(
                    mood = DogMood.LISTENING,
                    viseme = MouthViseme.REST,
                    statusText = message,
                    errorMessage = null
                )
            }
            viewModelScope.launch {
                delay(380)
                retryListening()
            }
        } else {
            setPhase(CallPhase.ERROR)
            _uiState.update {
                it.copy(
                    mood = DogMood.SERIOUS,
                    viseme = MouthViseme.REST,
                    statusText = message,
                    errorMessage = message
                )
            }
        }
    }

    override fun onTtsPreparing(percent: Int, message: String) {
        setPhase(CallPhase.STARTING)
        _uiState.update {
            it.copy(
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = if (percent in 1..99) "$message $percent%" else message,
                errorMessage = null
            )
        }
    }

    override fun onTtsReady() {
        ttsReady = true
        setPhase(CallPhase.STARTING)
        _uiState.update {
            it.copy(
                mood = DogMood.CALM,
                statusText = "الصوت السعودي الطبيعي جاهز"
            )
        }
        tryStartSession()
    }

    override fun onTtsStarted() {
        staffScenarioActive = false
        staffVoice.interrupt()
        cancelOfficeEvent()
        setPhase(CallPhase.SPEAKING)
        _uiState.update {
            it.copy(
                mood = if (it.mood == DogMood.SMILE || it.mood == DogMood.SERIOUS) it.mood else DogMood.TALKING,
                statusText = "الشرطي يتكلم…"
            )
        }
    }

    override fun onViseme(viseme: MouthViseme) {
        _uiState.update { it.copy(viseme = viseme) }
    }

    override fun onTtsFinished() {
        _uiState.update { it.copy(viseme = MouthViseme.REST) }
        if (!microphonePermissionGranted) return
        completedPoliceTurns += 1
        runOfficeBeatThenListen()
    }

    override fun onTtsError(message: String) {
        setPhase(CallPhase.ERROR)
        _uiState.update {
            it.copy(
                mood = DogMood.SERIOUS,
                viseme = MouthViseme.REST,
                statusText = message,
                errorMessage = message
            )
        }
    }

    private fun runOfficeBeatThenListen() {
        cancelOfficeEvent(resetScene = false)
        officeEventJob = viewModelScope.launch {
            when (completedPoliceTurns % 8) {
                2 -> runPaperBeat()
                4 -> runPhoneBeat()
                0 -> runDoorStaffBeat()
                else -> {
                    delay(120)
                    retryListening()
                }
            }
        }
    }

    private suspend fun runPaperBeat() {
        setPhase(CallPhase.THINKING)
        _uiState.update {
            it.copy(
                mood = DogMood.CALM,
                statusText = "…",
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.PAPER_RUSTLE,
                    attention = DogAttention.CAMERA,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        officeSoundscape.playCue(OfficeCue.PAPER_RUSTLE)
        delay(620)
        retryListening()
    }

    private suspend fun runPhoneBeat() {
        setPhase(CallPhase.THINKING)
        _uiState.update {
            it.copy(
                mood = DogMood.THINKING,
                statusText = "…",
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.PHONE_RING,
                    attention = DogAttention.PHONE,
                    phoneRinging = true,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        officeSoundscape.playCue(OfficeCue.PHONE_RING)
        delay(1500)
        _uiState.update {
            it.copy(
                mood = DogMood.CALM,
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.NONE,
                    attention = DogAttention.CAMERA,
                    phoneRinging = false,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        delay(180)
        retryListening()
    }

    private suspend fun runDoorStaffBeat() {
        staffScenarioActive = true
        setPhase(CallPhase.THINKING)
        _uiState.update {
            it.copy(
                mood = DogMood.LISTENING,
                statusText = "…",
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.DOOR_OPEN,
                    attention = DogAttention.DOOR,
                    doorOpen = true,
                    staffAtDoor = false,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        officeSoundscape.playCue(OfficeCue.DOOR_OPEN)
        delay(520)
        officeSoundscape.playCue(OfficeCue.FOOTSTEPS)
        _uiState.update {
            it.copy(
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.FOOTSTEPS,
                    attention = DogAttention.DOOR,
                    staffAtDoor = true,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        delay(720)

        val line = staffLines[(completedPoliceTurns / 8) % staffLines.size]
        _uiState.update {
            it.copy(
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.STAFF_SPEAK,
                    attention = DogAttention.STAFF,
                    staffAtDoor = true,
                    staffSpeaking = true,
                    staffLine = line,
                    revision = it.officeScene.revision + 1
                )
            )
        }

        if (staffVoiceReady) {
            officeEventJob = null
            staffVoice.speak(line)
        } else {
            delay(900)
            closeDoorThenListen()
        }
    }

    private fun closeDoorThenListen() {
        if (!staffScenarioActive) return
        staffScenarioActive = false
        officeEventJob?.cancel()
        officeEventJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    officeScene = it.officeScene.copy(
                        cue = OfficeCue.DOOR_CLOSE,
                        attention = DogAttention.DOOR,
                        staffSpeaking = false,
                        staffAtDoor = false,
                        staffLine = "",
                        revision = it.officeScene.revision + 1
                    )
                )
            }
            officeSoundscape.playCue(OfficeCue.DOOR_CLOSE)
            delay(520)
            retryListening()
        }
    }

    private fun setPhase(phase: CallPhase) {
        officeSoundscape.setConversationPhase(phase)
        _uiState.update { it.copy(phase = phase) }
    }

    private fun resetOfficeScene() {
        _uiState.update {
            it.copy(
                officeScene = OfficeSceneState(
                    staffVisible = true,
                    revision = it.officeScene.revision + 1
                )
            )
        }
    }

    private fun cancelOfficeEvent(resetScene: Boolean = true) {
        staffScenarioActive = false
        officeEventJob?.cancel()
        officeEventJob = null
        if (resetScene) resetOfficeScene()
    }

    override fun onCleared() {
        officeEventJob?.cancel()
        staffVoice.release()
        officeSoundscape.release()
        voiceEngine.release()
        super.onCleared()
    }

    private companion object {
        const val PREFS_NAME = "alshurti_voice_settings"
        const val KEY_MODE = "voice_mode"

        val staffLines = listOf(
            "سيدي، الملف جاهز.",
            "سيدي، التقرير وصل.",
            "تمام سيدي، بخليه على المكتب."
        )
    }
}
