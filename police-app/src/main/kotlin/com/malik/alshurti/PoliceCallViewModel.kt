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

    private val initialMode = runCatching {
        VoiceMode.valueOf(preferences.getString(KEY_MODE, VoiceMode.ONLINE.name) ?: VoiceMode.ONLINE.name)
    }.getOrDefault(VoiceMode.ONLINE)

    private val brain: PoliceBrain = QwenPoliceBrain(application.applicationContext)
    private val voiceEngine = PoliceVoiceEngine(application.applicationContext, this)

    private val _uiState = MutableStateFlow(PoliceUiState(mode = initialMode))
    val uiState: StateFlow<PoliceUiState> = _uiState.asStateFlow()

    private var microphonePermissionGranted = false
    private var ttsReady = false
    private var sessionStarted = false
    private var conversationLoopEnabled = false
    private var voicePreviewOnly = false

    init {
        // First launch must stay lightweight. Neither Qwen nor the 318 MB Arabic STT
        // model is provisioned just because this screen opened.
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
                    errorMessage = "إذن الميكروفون مطلوب للمحادثة الصوتية.",
                    readyToStart = false
                )
            }
            return
        }
        tryStartSession()
    }

    fun chooseMode(mode: VoiceMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        ttsReady = false
        sessionStarted = false
        conversationLoopEnabled = false
        voicePreviewOnly = false

        _uiState.update {
            it.copy(
                mode = mode,
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = if (mode == VoiceMode.ONLINE) {
                    "جاري تجهيز الصوت السعودي…"
                } else {
                    "جاري تشغيل المحركات المحلية…"
                },
                errorMessage = null,
                readyToStart = false
            )
        }

        voiceEngine.setMode(mode)
    }

    /**
     * Explicitly starts the heavy listening path. Until the user taps this, opening
     * the screen and previewing the voice never downloads the Arabic STT model.
     */
    fun startConversation() {
        if (!microphonePermissionGranted) return
        conversationLoopEnabled = true
        voicePreviewOnly = false
        _uiState.update { it.copy(readyToStart = false, errorMessage = null) }
        retryListening()
    }

    /** Play only the Saudi TTS so the user can judge voice quality immediately. */
    fun testSaudiVoice() {
        if (_uiState.value.mode != VoiceMode.ONLINE) {
            chooseMode(VoiceMode.ONLINE)
        }
        conversationLoopEnabled = false
        voicePreviewOnly = true
        val preview = "هلا، أنا الشرطي. الصوت واضح عندك؟ أنا معك واسمعك زين."
        _uiState.update {
            it.copy(
                phase = CallPhase.SPEAKING,
                mood = DogMood.SMILE,
                replyText = preview,
                statusText = "اختبار الصوت السعودي…",
                errorMessage = null,
                readyToStart = false
            )
        }
        voiceEngine.speak(preview)
    }

    /**
     * Qwen is hundreds of megabytes, so provisioning it is an explicit user action.
     * The download runs in the background and never blocks initial voice preview.
     */
    fun downloadLocalConversationModel() {
        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                statusText = "بدأ تنزيل نموذج المحادثة المحلي في الخلفية…",
                errorMessage = null
            )
        }
        brain.prepare(allowDownload = true)
    }

    fun retryListening() {
        if (!microphonePermissionGranted) return
        conversationLoopEnabled = true
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
        conversationLoopEnabled = true
        voicePreviewOnly = false
        voiceEngine.interruptSpeech()
        retryListening()
    }

    private fun tryStartSession() {
        if (!microphonePermissionGranted || !ttsReady || sessionStarted) return
        sessionStarted = true

        if (!_uiState.value.firstGreetingDone) {
            conversationLoopEnabled = false
            voicePreviewOnly = false
            val greeting = "هلا، أنا معك. وش صار؟"
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
        } else {
            _uiState.update {
                it.copy(
                    phase = CallPhase.STARTING,
                    mood = DogMood.CALM,
                    statusText = "الصوت جاهز — اضغط بدء المحادثة",
                    readyToStart = true,
                    errorMessage = null
                )
            }
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
                statusText = "ثانية…",
                errorMessage = null,
                readyToStart = false
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
                    val message = error.message ?: "تعذر تجهيز الرد."
                    _uiState.update {
                        it.copy(
                            phase = CallPhase.ERROR,
                            mood = DogMood.SERIOUS,
                            viseme = MouthViseme.REST,
                            statusText = message.take(110),
                            errorMessage = message,
                            readyToStart = true
                        )
                    }
                }
        }
    }

    override fun onSpeechPreparing(percent: Int, message: String) {
        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = if (percent in 1..99) "$message $percent%" else message,
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
        if (recoverable && microphonePermissionGranted && conversationLoopEnabled) {
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
                    errorMessage = message,
                    readyToStart = microphonePermissionGranted
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
                statusText = "الصوت السعودي جاهز"
            )
        }
        tryStartSession()
    }

    override fun onTtsStarted() {
        _uiState.update {
            it.copy(
                phase = CallPhase.SPEAKING,
                mood = if (it.mood == DogMood.SMILE || it.mood == DogMood.SERIOUS) it.mood else DogMood.TALKING,
                statusText = if (voicePreviewOnly) "اختبار الصوت السعودي…" else "الشرطي يتكلم…",
                readyToStart = false
            )
        }
    }

    override fun onViseme(viseme: MouthViseme) {
        _uiState.update { it.copy(viseme = viseme) }
    }

    override fun onTtsFinished() {
        _uiState.update { it.copy(viseme = MouthViseme.REST) }

        if (voicePreviewOnly) {
            voicePreviewOnly = false
            _uiState.update {
                it.copy(
                    phase = CallPhase.STARTING,
                    mood = DogMood.CALM,
                    statusText = "اختبار الصوت انتهى — الصوت جاهز",
                    readyToStart = it.firstGreetingDone && microphonePermissionGranted
                )
            }
            return
        }

        if (!microphonePermissionGranted) return
        if (!conversationLoopEnabled) {
            _uiState.update {
                it.copy(
                    phase = CallPhase.STARTING,
                    mood = DogMood.CALM,
                    statusText = "الصوت جاهز — اضغط بدء المحادثة",
                    readyToStart = true
                )
            }
            return
        }

        viewModelScope.launch {
            delay(100)
            retryListening()
        }
    }

    override fun onTtsError(message: String) {
        voicePreviewOnly = false
        _uiState.update {
            it.copy(
                phase = CallPhase.ERROR,
                mood = DogMood.SERIOUS,
                viseme = MouthViseme.REST,
                statusText = message,
                errorMessage = message,
                readyToStart = false
            )
        }
    }

    override fun onCleared() {
        brain.release()
        voiceEngine.release()
        super.onCleared()
    }

    private companion object {
        const val PREFS_NAME = "alshurti_voice_settings"
        const val KEY_MODE = "voice_mode"
    }
}
