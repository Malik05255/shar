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

    private val _uiState = MutableStateFlow(
        PoliceUiState(
            mode = initialMode,
            statusText = "جاري فتح المكالمة…",
            readyToStart = false
        )
    )
    val uiState: StateFlow<PoliceUiState> = _uiState.asStateFlow()

    private var microphonePermissionGranted = false
    private var conversationLoopEnabled = false
    private var pendingConversationStart = false
    private var autoStartAttempted = false

    init {
        voiceEngine.setMode(initialMode)
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
        voiceEngine.setMode(mode)

        _uiState.update {
            it.copy(
                mode = mode,
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = if (mode == VoiceMode.ONLINE) {
                    "وضع الإنترنت — جاري إعادة الاتصال…"
                } else {
                    "بدون إنترنت — جاري فتح المكالمة…"
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
        voiceEngine.stopListening()
        voiceEngine.interruptSpeech()

        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                heardText = "",
                statusText = "جاري الاتصال بالشرطي…",
                errorMessage = null,
                readyToStart = false
            )
        }

        // Voice comes first so the child hears the greeting immediately. The local
        // conversational model warms in the background after the greeting starts.
        voiceEngine.prepareVoice()
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
        pendingConversationStart = false
        conversationLoopEnabled = true
        voiceEngine.interruptSpeech()
        retryListening()
    }

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
            runCatching { brain.reply(clean) }
                .onSuccess { reply ->
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
                            statusText = "تعذر تجهيز الرد. اضغط إعادة المحاولة.",
                            errorMessage = error.message ?: "تعذر تجهيز الرد.",
                            readyToStart = false
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
                statusText = if (percent in 1..99) {
                    "جاري تجهيز الاستماع لأول مرة… $percent%"
                } else {
                    "جاري تجهيز الاستماع…"
                },
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
        if (recoverable && microphonePermissionGranted && conversationLoopEnabled) {
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
        } else {
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
                statusText = "جاري تجهيز صوت الشرطي…",
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

        // Warm Qwen only after audio has been launched so model work never delays the greeting.
        brain.prepare(allowDownload = _uiState.value.mode == VoiceMode.ONLINE)
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
                statusText = "تعذر تشغيل الصوت. غيّر وضع الاتصال أو اضغط إعادة المحاولة.",
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
