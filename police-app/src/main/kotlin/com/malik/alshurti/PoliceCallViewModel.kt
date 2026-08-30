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
            statusText = if (initialMode == VoiceMode.ONLINE) {
                "جاهز — أول تشغيل فقط يحتاج تنزيل المحركات المحلية"
            } else {
                "بدون إنترنت — يستخدم المحركات المثبتة على الجهاز"
            },
            readyToStart = true
        )
    )
    val uiState: StateFlow<PoliceUiState> = _uiState.asStateFlow()

    private var microphonePermissionGranted = false
    private var ttsReady = false
    private var conversationLoopEnabled = false
    private var voicePreviewOnly = false
    private var pendingConversationStart = false
    private var pendingSpeech: String? = null

    init {
        // Policy only. This call never downloads or initializes a model.
        voiceEngine.setMode(initialMode)
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        microphonePermissionGranted = granted
        if (!granted) {
            _uiState.update {
                it.copy(
                    phase = CallPhase.ERROR,
                    mood = DogMood.SERIOUS,
                    statusText = "اسمح للشرطي باستخدام الميكروفون حتى يسمعك.",
                    errorMessage = "إذن الميكروفون مطلوب للمحادثة الصوتية.",
                    readyToStart = false
                )
            }
        } else {
            _uiState.update {
                if (it.phase == CallPhase.ERROR && it.errorMessage?.contains("الميكروفون") == true) {
                    it.copy(
                        phase = CallPhase.STARTING,
                        mood = DogMood.CALM,
                        statusText = "جاهز — اضغط بدء المحادثة",
                        errorMessage = null,
                        readyToStart = true
                    )
                } else {
                    it.copy(readyToStart = true)
                }
            }
        }
    }

    fun chooseMode(mode: VoiceMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        conversationLoopEnabled = false
        voicePreviewOnly = false
        pendingConversationStart = false
        pendingSpeech = null
        ttsReady = false
        voiceEngine.setMode(mode)

        _uiState.update {
            it.copy(
                mode = mode,
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = if (mode == VoiceMode.ONLINE) {
                    "الإنترنت يسمح بتنزيل المحركات الناقصة مرة واحدة"
                } else {
                    "بدون إنترنت — لن يتم أي تنزيل"
                },
                errorMessage = null,
                readyToStart = microphonePermissionGranted
            )
        }
    }

    /**
     * Starts the actual experience. Nothing heavy runs until this explicit action.
     * ONLINE may provision missing local models once; OFFLINE never reaches the network.
     */
    fun startConversation() {
        if (!microphonePermissionGranted) return

        conversationLoopEnabled = false
        voicePreviewOnly = false
        pendingConversationStart = true
        pendingSpeech = null

        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                statusText = if (_uiState.value.mode == VoiceMode.ONLINE) {
                    "جاري تجهيز المحادثة المحلية — أول مرة فقط…"
                } else {
                    "جاري فتح المحركات المحلية…"
                },
                errorMessage = null,
                readyToStart = false
            )
        }

        brain.prepare(allowDownload = _uiState.value.mode == VoiceMode.ONLINE)
        voiceEngine.prepareVoice()
    }

    /** Tests only local neural TTS; no microphone, Whisper or Qwen is needed. */
    fun testSaudiVoice() {
        conversationLoopEnabled = false
        pendingConversationStart = false
        voicePreviewOnly = true
        val preview = "هلا يا بطل، معك الشرطي. أنا سامعك وواضح عندي، وش عندك اليوم؟"
        pendingSpeech = preview

        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.SMILE,
                replyText = preview,
                statusText = if (it.mode == VoiceMode.ONLINE) {
                    "جاري تجهيز الصوت العربي المحلي — أول مرة فقط…"
                } else {
                    "جاري فتح الصوت العربي المحلي…"
                },
                errorMessage = null,
                readyToStart = false
            )
        }
        voiceEngine.prepareVoice()
    }

    /** Optional pre-download for the local conversational brain. */
    fun downloadLocalConversationModel() {
        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                statusText = "بدأ تجهيز نموذج المحادثة المحلي في الخلفية…",
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
        voicePreviewOnly = false
        pendingSpeech = null
        pendingConversationStart = false
        conversationLoopEnabled = true
        voiceEngine.interruptSpeech()
        retryListening()
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
                    conversationLoopEnabled = false
                    _uiState.update {
                        it.copy(
                            phase = CallPhase.ERROR,
                            mood = DogMood.SERIOUS,
                            viseme = MouthViseme.REST,
                            statusText = message.take(120),
                            errorMessage = message,
                            readyToStart = microphonePermissionGranted
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
                delay(300)
                retryListening()
            }
        } else {
            conversationLoopEnabled = false
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
                errorMessage = null,
                readyToStart = false
            )
        }
    }

    override fun onTtsReady() {
        ttsReady = true

        pendingSpeech?.let { text ->
            pendingSpeech = null
            _uiState.update {
                it.copy(
                    phase = CallPhase.SPEAKING,
                    mood = DogMood.SMILE,
                    statusText = "جاري تشغيل اختبار الصوت…"
                )
            }
            voiceEngine.speak(text)
            return
        }

        if (pendingConversationStart) {
            pendingConversationStart = false
            conversationLoopEnabled = true
            val greeting = "هلا يا بطل، معك الشرطي. وش عندك؟"
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
            return
        }

        _uiState.update {
            it.copy(
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                statusText = "الصوت المحلي جاهز",
                readyToStart = microphonePermissionGranted
            )
        }
    }

    override fun onTtsStarted() {
        _uiState.update {
            it.copy(
                phase = CallPhase.SPEAKING,
                mood = if (it.mood == DogMood.SMILE || it.mood == DogMood.SERIOUS) it.mood else DogMood.TALKING,
                statusText = if (voicePreviewOnly) "اختبار الصوت المحلي…" else "الشرطي يتكلم…",
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
                    statusText = "اختبار الصوت انتهى — الصوت محفوظ محلياً",
                    readyToStart = microphonePermissionGranted
                )
            }
            return
        }

        if (conversationLoopEnabled && microphonePermissionGranted) {
            viewModelScope.launch {
                delay(90)
                retryListening()
            }
        } else {
            _uiState.update {
                it.copy(
                    phase = CallPhase.STARTING,
                    mood = DogMood.CALM,
                    statusText = "جاهز — اضغط بدء المحادثة",
                    readyToStart = microphonePermissionGranted
                )
            }
        }
    }

    override fun onTtsError(message: String) {
        voicePreviewOnly = false
        pendingSpeech = null
        pendingConversationStart = false
        conversationLoopEnabled = false
        ttsReady = false
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
