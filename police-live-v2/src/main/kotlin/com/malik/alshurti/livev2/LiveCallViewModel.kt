package com.malik.alshurti.livev2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LiveCallViewModel(application: Application) : AndroidViewModel(application), GeminiLiveSession.Callbacks {
    data class UiState(
        val sessionState: GeminiLiveSession.State = GeminiLiveSession.State.IDLE,
        val status: String = "جاهز",
        val userText: String = "",
        val policeText: String = "",
        val inputLevel: Float = 0f,
        val outputLevel: Float = 0f,
        val permissionGranted: Boolean = false,
        val callActive: Boolean = false,
        val error: String? = null
    )

    private val session = GeminiLiveSession(application.applicationContext, this)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun setMicrophonePermission(granted: Boolean) {
        _ui.update {
            it.copy(
                permissionGranted = granted,
                status = if (granted) "بدء مكالمة LIVE" else "اسمح باستخدام الميكروفون",
                error = if (granted) null else "إذن الميكروفون مطلوب"
            )
        }
        if (granted && !_ui.value.callActive) startCall()
    }

    fun startCall() {
        if (!_ui.value.permissionGranted || _ui.value.callActive) return
        _ui.update {
            it.copy(
                callActive = true,
                status = "فتح جلسة صوتية مباشرة…",
                error = null,
                userText = "",
                policeText = ""
            )
        }
        session.connect()
    }

    fun retry() {
        if (!_ui.value.permissionGranted) return
        _ui.update { it.copy(callActive = true, error = null, userText = "", policeText = "") }
        session.reconnect()
    }

    fun endCall() {
        session.disconnect()
        _ui.update {
            it.copy(
                callActive = false,
                sessionState = GeminiLiveSession.State.CLOSED,
                status = "انتهت المكالمة",
                inputLevel = 0f,
                outputLevel = 0f
            )
        }
    }

    override fun onState(state: GeminiLiveSession.State, detail: String) {
        _ui.update {
            it.copy(
                sessionState = state,
                status = detail.ifBlank { state.name },
                callActive = state !in setOf(GeminiLiveSession.State.ERROR, GeminiLiveSession.State.CLOSED, GeminiLiveSession.State.IDLE),
                error = if (state == GeminiLiveSession.State.ERROR) detail else null
            )
        }
    }

    override fun onUserText(text: String, interim: Boolean) {
        _ui.update { it.copy(userText = text.trim(), error = null) }
    }

    override fun onPoliceText(text: String) {
        _ui.update { it.copy(policeText = text.trim(), error = null) }
    }

    override fun onInputLevel(level: Float) {
        _ui.update { it.copy(inputLevel = level.coerceIn(0f, 1f)) }
    }

    override fun onOutputLevel(level: Float) {
        _ui.update { it.copy(outputLevel = level.coerceIn(0f, 1f)) }
    }

    override fun onCleared() {
        session.release()
        super.onCleared()
    }
}
