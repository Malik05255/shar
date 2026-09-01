package com.malik.alshurti.livev2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LivePoliceV2ViewModel(application: Application) : AndroidViewModel(application), GeminiLiveAudioEngine.Listener {
    data class UiState(
        val state: GeminiLiveAudioEngine.State = GeminiLiveAudioEngine.State.IDLE,
        val status: String = "جاهز للاتصال",
        val userText: String = "",
        val policeText: String = "",
        val inputLevel: Float = 0f,
        val outputLevel: Float = 0f,
        val microphoneGranted: Boolean = false,
        val started: Boolean = false,
        val error: String? = null
    )

    private val engine = GeminiLiveAudioEngine(application.applicationContext, this)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onMicrophonePermission(granted: Boolean) {
        _uiState.update {
            it.copy(
                microphoneGranted = granted,
                error = if (granted) null else "إذن الميكروفون مطلوب للمكالمة الحية.",
                status = if (granted) "يمكن بدء المكالمة" else "اسمح باستخدام الميكروفون"
            )
        }
        if (granted && !_uiState.value.started) start()
    }

    fun start() {
        if (!_uiState.value.microphoneGranted) return
        _uiState.update { it.copy(started = true, error = null, status = "فتح جلسة Live…") }
        engine.start()
    }

    fun retry() {
        if (!_uiState.value.microphoneGranted) return
        _uiState.update { it.copy(started = true, error = null, userText = "", policeText = "") }
        engine.restart()
    }

    fun hangUp() {
        engine.stop()
        _uiState.update {
            it.copy(
                started = false,
                state = GeminiLiveAudioEngine.State.CLOSED,
                status = "انتهت المكالمة",
                inputLevel = 0f,
                outputLevel = 0f
            )
        }
    }

    override fun onState(state: GeminiLiveAudioEngine.State, detail: String?) {
        val status = detail ?: when (state) {
            GeminiLiveAudioEngine.State.IDLE -> "جاهز"
            GeminiLiveAudioEngine.State.CONNECTING -> "جاري الاتصال…"
            GeminiLiveAudioEngine.State.READY -> "اتصل"
            GeminiLiveAudioEngine.State.LISTENING -> "تكلم… أنا أسمعك"
            GeminiLiveAudioEngine.State.USER_SPEAKING -> "أسمعك…"
            GeminiLiveAudioEngine.State.MODEL_SPEAKING -> "الشرطي يتكلم…"
            GeminiLiveAudioEngine.State.ERROR -> "حدث خطأ"
            GeminiLiveAudioEngine.State.CLOSED -> "انتهت المكالمة"
        }
        _uiState.update {
            it.copy(
                state = state,
                status = status,
                error = if (state == GeminiLiveAudioEngine.State.ERROR) status else null,
                started = state !in setOf(GeminiLiveAudioEngine.State.ERROR, GeminiLiveAudioEngine.State.CLOSED)
            )
        }
    }

    override fun onUserTranscript(text: String, interim: Boolean) {
        _uiState.update { it.copy(userText = text.trim(), error = null) }
    }

    override fun onModelTranscript(text: String) {
        _uiState.update { it.copy(policeText = text.trim(), error = null) }
    }

    override fun onInputLevel(level: Float) {
        _uiState.update { it.copy(inputLevel = level.coerceIn(0f, 1f)) }
    }

    override fun onOutputLevel(level: Float) {
        _uiState.update { it.copy(outputLevel = level.coerceIn(0f, 1f)) }
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
