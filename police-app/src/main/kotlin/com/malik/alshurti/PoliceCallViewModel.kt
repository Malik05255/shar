package com.malik.alshurti

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PoliceCallViewModel(application: Application) : AndroidViewModel(application), PoliceVoiceEngine.Listener {
    private val preferences = application.getSharedPreferences(PREFS_NAME, 0)

    // Quality contract: production speech is the native Saudi online voice only.
    // Ignore any OFFLINE value saved by an older build so an upgrade cannot silently
    // return the user to the robotic/local voice path.
    private val initialMode = VoiceMode.ONLINE

    private val brain: PoliceBrain = LocalPoliceBrain()
    private val voiceEngine = PoliceVoiceEngine(application.applicationContext, this)

    private val _uiState = MutableStateFlow(PoliceUiState(mode = initialMode))
    val uiState: StateFlow<PoliceUiState> = _uiState.asStateFlow()

    private var microphonePermissionGranted = false
    private var ttsReady = false
    private var sessionStarted = false

    init {
        preferences.edit().putString(KEY_MODE, VoiceMode.ONLINE.name).apply()
        voiceEngine.setMode(initialMode)
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        microphonePermissionGranted = granted
        if (!granted) {
            _uiState.update {
                it.copy(
                    phase = CallPhase.ERROR,
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
            _uiState.update {
                it.copy(
                    mode = VoiceMode.ONLINE,
                    phase = CallPhase.ERROR,
                    mood = DogMood.SERIOUS,
                    viseme = MouthViseme.REST,
                    statusText = "الصوت السعودي الطبيعي يعمل عبر الإنترنت فقط.",
                    errorMessage = "تم تعطيل الصوت المحلي لأنه لا يحقق الجودة المطلوبة."
                )
            }
            return
        }

        preferences.edit().putString(KEY_MODE, VoiceMode.ONLINE.name).apply()
        ttsReady = false
        sessionStarted = false
        voiceEngine.setMode(VoiceMode.ONLINE)
        _uiState.update {
            it.copy(
                mode = VoiceMode.ONLINE,
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = "جاري تجهيز الصوت السعودي الطبيعي…",
                errorMessage = null
            )
        }
    }

    fun retryListening() {
        if (!microphonePermissionGranted) return
        _uiState.update {
            it.copy(
                phase = CallPhase.LISTENING,
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
        retryListening()
    }

    private fun tryStartSession() {
        if (!microphonePermissionGranted || !ttsReady || sessionStarted) return
        sessionStarted = true

        if (!_uiState.value.firstGreetingDone) {
            val greeting = "هلا يا بطل، معك الشرطي. وش عندك؟"
            _uiState.update {
                it.copy(
                    phase = CallPhase.SPEAKING,
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

        _uiState.update {
            it.copy(
                heardText = text,
                phase = CallPhase.THINKING,
                mood = DogMood.THINKING,
                viseme = MouthViseme.REST,
                statusText = "لحظة… أفكر في كلامك",
                errorMessage = null
            )
        }

        viewModelScope.launch {
            runCatching { brain.reply(text) }
                .onSuccess { reply ->
                    _uiState.update {
                        it.copy(
                            replyText = reply.text,
                            phase = CallPhase.SPEAKING,
                            mood = reply.mood,
                            statusText = "الشرطي يرد عليك…"
                        )
                    }
                    voiceEngine.speak(reply.text)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            phase = CallPhase.ERROR,
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
        _uiState.update {
            it.copy(
                phase = CallPhase.LISTENING,
                mood = DogMood.LISTENING,
                viseme = MouthViseme.REST,
                statusText = "تكلم… أنا أسمعك",
                errorMessage = null
            )
        }
    }

    override fun onSpeechStarted() {
        _uiState.update {
            it.copy(
                phase = CallPhase.LISTENING,
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
            _uiState.update {
                it.copy(
                    phase = CallPhase.LISTENING,
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
            _uiState.update {
                it.copy(
                    phase = CallPhase.ERROR,
                    mood = DogMood.SERIOUS,
                    viseme = MouthViseme.REST,
                    statusText = message,
                    errorMessage = message
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
                statusText = if (percent in 1..99) "$message $percent%" else message,
                errorMessage = null
            )
        }
    }

    override fun onTtsReady() {
        ttsReady = true
        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                statusText = "الصوت السعودي الطبيعي جاهز"
            )
        }
        tryStartSession()
    }

    override fun onTtsStarted() {
        _uiState.update {
            it.copy(
                phase = CallPhase.SPEAKING,
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
        viewModelScope.launch {
            delay(100)
            retryListening()
        }
    }

    override fun onTtsError(message: String) {
        _uiState.update {
            it.copy(
                phase = CallPhase.ERROR,
                mood = DogMood.SERIOUS,
                viseme = MouthViseme.REST,
                statusText = message,
                errorMessage = message
            )
        }
    }

    override fun onCleared() {
        voiceEngine.release()
        super.onCleared()
    }

    private companion object {
        const val PREFS_NAME = "alshurti_voice_settings"
        const val KEY_MODE = "voice_mode"
    }
}
