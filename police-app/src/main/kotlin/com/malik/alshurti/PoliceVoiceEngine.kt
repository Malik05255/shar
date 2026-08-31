package com.malik.alshurti

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.malik.alshurti.neural.NeuralArabicVoice
import com.malik.alshurti.voice.GeminiSilentListener
import com.malik.alshurti.voice.OfflineArabicListener
import com.malik.alshurti.voice.SaudiHumanVoice

/**
 * Local-first half-duplex voice coordinator.
 *
 * Preferred steady-state path:
 *   microphone -> quiet AudioRecord/VAD -> local Whisper -> LocalPoliceBrain -> local Supertonic.
 *
 * Gemini remains an optional online accelerator/fallback when a development key is configured.
 * After the local Whisper and Supertonic files have been downloaded once, conversation no longer
 * requires an API key, a per-minute quota, or a network connection.
 */
class PoliceVoiceEngine(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onReadyToListen()
        fun onSpeechStarted()
        fun onPartialText(text: String)
        fun onFinalText(text: String)
        fun onSpeechError(message: String, recoverable: Boolean)
        fun onTtsPreparing(percent: Int, message: String)
        fun onTtsReady()
        fun onTtsStarted()
        fun onTtsFinished()
        fun onTtsError(message: String)
        fun onViseme(viseme: MouthViseme)
    }

    private enum class SpeechBackend { NONE, LOCAL, CLOUD }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mode: VoiceMode = VoiceMode.ONLINE
    private var spokenText = ""
    private var lastViseme = MouthViseme.REST
    private var observerHasSpoken = false
    private var ttsRetryCount = 0
    private var fallbackAttempted = false
    private var released = false
    private var readyReported = false
    private var localTtsReady = false
    private var cloudTtsReady = false
    private var localAsrReady = false
    private var activeSpeechBackend = SpeechBackend.NONE

    private val cloudAvailable: Boolean
        get() = BuildConfig.GEMINI_API_KEY.trim().isNotBlank()

    private val silentListener: GeminiSilentListener = GeminiSilentListener(
        context = context.applicationContext,
        callbacks = object : GeminiSilentListener.Callbacks {
            override fun onReady() {
                listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                observerHasSpoken = true
                listener.onSpeechStarted()
            }

            override fun onFinalText(text: String) {
                if (text.isNotBlank()) observerHasSpoken = true
                listener.onFinalText(text)
            }

            override fun onError(message: String, recoverable: Boolean) {
                listener.onSpeechError(message, recoverable)
            }
        }
    )

    private val offlineListener: OfflineArabicListener = OfflineArabicListener(
        context = context.applicationContext,
        callbacks = object : OfflineArabicListener.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (!readyReported) listener.onTtsPreparing(percent, message)
            }

            override fun onPrepared() {
                localAsrReady = true
                maybeReportReady()
            }

            override fun onReady() {
                listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                observerHasSpoken = true
                listener.onSpeechStarted()
            }

            override fun onFinalText(text: String) {
                if (text.isNotBlank()) observerHasSpoken = true
                listener.onFinalText(text)
            }

            override fun onError(message: String, recoverable: Boolean) {
                if (!localAsrReady && mode == VoiceMode.ONLINE && cloudAvailable) {
                    maybeReportReady()
                } else {
                    listener.onSpeechError(message, recoverable)
                }
            }
        }
    )

    private val localVoice: NeuralArabicVoice = NeuralArabicVoice(
        context = context.applicationContext,
        callbacks = object : NeuralArabicVoice.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (mode == VoiceMode.OFFLINE || !cloudTtsReady) {
                    listener.onTtsPreparing(percent, message)
                }
            }

            override fun onReady() {
                localTtsReady = true
                maybeReportReady()
            }

            override fun onSpeechStarted(durationMs: Long) {
                if (activeSpeechBackend == SpeechBackend.LOCAL) handleSpeechStarted()
            }

            override fun onSpeechCursor(fraction: Float) {
                if (activeSpeechBackend == SpeechBackend.LOCAL) handleSpeechCursor(fraction)
            }

            override fun onSpeechFinished() {
                if (activeSpeechBackend == SpeechBackend.LOCAL) handleSpeechFinished()
            }

            override fun onError(message: String) {
                if (activeSpeechBackend == SpeechBackend.LOCAL &&
                    mode == VoiceMode.ONLINE && cloudTtsReady && !fallbackAttempted && spokenText.isNotBlank()
                ) {
                    fallbackAttempted = true
                    activeSpeechBackend = SpeechBackend.CLOUD
                    saudiVoice.speak(spokenText)
                } else if (!localTtsReady && mode == VoiceMode.ONLINE && cloudTtsReady) {
                    maybeReportReady()
                } else {
                    resetMouth()
                    listener.onTtsError(message)
                }
            }
        }
    )

    private val saudiVoice: SaudiHumanVoice = SaudiHumanVoice(
        context = context.applicationContext,
        callbacks = object : SaudiHumanVoice.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (!observerHasSpoken && !localTtsReady) listener.onTtsPreparing(percent, message)
            }

            override fun onReady() {
                cloudTtsReady = true
                maybeReportReady()
            }

            override fun onSpeechStarted(durationMs: Long) {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleSpeechStarted()
            }

            override fun onSpeechCursor(fraction: Float) {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleSpeechCursor(fraction)
            }

            override fun onSpeechFinished() {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleSpeechFinished()
            }

            override fun onError(message: String) {
                if (activeSpeechBackend == SpeechBackend.CLOUD && localTtsReady && !fallbackAttempted && spokenText.isNotBlank()) {
                    fallbackAttempted = true
                    activeSpeechBackend = SpeechBackend.LOCAL
                    localVoice.speak(spokenText)
                } else {
                    handleCloudVoiceError(message)
                }
            }
        }
    )

    fun setMode(newMode: VoiceMode) {
        mode = newMode
        stopListening()
        interruptSpeech()
        observerHasSpoken = false
        ttsRetryCount = 0
        fallbackAttempted = false
        readyReported = false

        when (newMode) {
            VoiceMode.OFFLINE -> {
                offlineListener.prepare(allowDownload = false)
                localVoice.prepare(allowDownload = false)
            }
            VoiceMode.ONLINE -> {
                offlineListener.prepare(allowDownload = true)
                localVoice.prepare(allowDownload = true)
                if (cloudAvailable) saudiVoice.prepare() else cloudTtsReady = false
            }
        }
        maybeReportReady()
    }

    fun startListening() {
        when {
            localAsrReady -> offlineListener.start()
            mode == VoiceMode.ONLINE && cloudAvailable -> silentListener.start()
            else -> listener.onSpeechError(
                if (mode == VoiceMode.OFFLINE) "الاستماع المحلي غير مجهز بعد." else "جاري تجهيز الاستماع المحلي.",
                true
            )
        }
    }

    fun stopListening() {
        offlineListener.stop()
        silentListener.stop()
    }

    fun interruptSpeech() {
        ttsRetryCount = 0
        fallbackAttempted = false
        mainHandler.removeCallbacksAndMessages(null)
        localVoice.interrupt()
        saudiVoice.interrupt()
        activeSpeechBackend = SpeechBackend.NONE
        resetMouth()
    }

    fun speak(text: String) {
        spokenText = text.trim()
        ttsRetryCount = 0
        fallbackAttempted = false

        if (!observerHasSpoken && isPassiveOpeningGreeting(spokenText)) {
            spokenText = ""
            startListening()
            return
        }

        stopListening()
        if (spokenText.isBlank()) {
            listener.onTtsFinished()
            return
        }

        when {
            localTtsReady -> {
                activeSpeechBackend = SpeechBackend.LOCAL
                localVoice.speak(spokenText)
            }
            mode == VoiceMode.ONLINE && cloudTtsReady -> {
                activeSpeechBackend = SpeechBackend.CLOUD
                saudiVoice.speak(spokenText)
            }
            else -> {
                activeSpeechBackend = SpeechBackend.NONE
                listener.onTtsError("الصوت المحلي ما زال قيد التجهيز.")
            }
        }
    }

    fun release() {
        released = true
        ttsRetryCount = 0
        fallbackAttempted = false
        mainHandler.removeCallbacksAndMessages(null)
        stopListening()
        offlineListener.release()
        silentListener.release()
        localVoice.release()
        saudiVoice.release()
        activeSpeechBackend = SpeechBackend.NONE
        resetMouth()
    }

    private fun maybeReportReady() {
        if (released || readyReported) return
        val inputReady = localAsrReady || (mode == VoiceMode.ONLINE && cloudAvailable)
        val outputReady = localTtsReady || (mode == VoiceMode.ONLINE && cloudTtsReady)
        if (inputReady && outputReady) {
            readyReported = true
            listener.onTtsReady()
        }
    }

    private fun handleSpeechStarted() {
        ttsRetryCount = 0
        lastViseme = MouthViseme.OPEN
        dispatchViseme(lastViseme)
        listener.onTtsStarted()
    }

    private fun handleSpeechCursor(fraction: Float) {
        val viseme = visemeAtFraction(spokenText, fraction)
        if (viseme != lastViseme) {
            lastViseme = viseme
            dispatchViseme(viseme)
        }
    }

    private fun handleSpeechFinished() {
        ttsRetryCount = 0
        activeSpeechBackend = SpeechBackend.NONE
        resetMouth()
        listener.onTtsFinished()
    }

    private fun dispatchViseme(viseme: MouthViseme) {
        RuntimeOfficePlanBus.publishViseme(viseme)
        listener.onViseme(viseme)
    }

    private fun resetMouth() {
        lastViseme = MouthViseme.REST
        dispatchViseme(MouthViseme.REST)
    }

    private fun handleCloudVoiceError(message: String) {
        if (activeSpeechBackend == SpeechBackend.CLOUD &&
            shouldRetryVoice(message) && ttsRetryCount < MAX_TTS_RETRIES && spokenText.isNotBlank()
        ) {
            val retryNumber = ++ttsRetryCount
            val retryText = spokenText
            mainHandler.postDelayed({ retryCloudSpeech(retryText) }, RETRY_BASE_DELAY_MS * retryNumber)
        } else if (!cloudTtsReady && localTtsReady) {
            maybeReportReady()
        } else {
            activeSpeechBackend = SpeechBackend.NONE
            resetMouth()
            listener.onTtsError(message)
        }
    }

    private fun retryCloudSpeech(expectedText: String) {
        if (released || mode != VoiceMode.ONLINE || expectedText != spokenText || expectedText.isBlank()) return
        activeSpeechBackend = SpeechBackend.CLOUD
        saudiVoice.speak(expectedText)
    }

    private fun shouldRetryVoice(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("429") ||
            normalized.contains("حد استخدام") ||
            normalized.contains("مؤقت") ||
            normalized.contains("temporarily") ||
            normalized.contains("timeout")
    }

    private fun isPassiveOpeningGreeting(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        return normalized == "هلا يا بطل، معك الشرطي. وش عندك؟" ||
            normalized == "هلا يا بطل، معك الشرطي، وش عندك؟"
    }

    private fun visemeAtFraction(text: String, fraction: Float): MouthViseme {
        if (text.isBlank()) return MouthViseme.REST
        val position = ((text.length - 1) * fraction.coerceIn(0f, 1f)).toInt()
        val radius = 3
        val from = (position - radius).coerceAtLeast(0)
        val to = (position + radius + 1).coerceAtMost(text.length)
        val letter = text.substring(from, to)
            .firstOrNull { it.isLetter() }
            ?: return MouthViseme.REST
        return when (letter) {
            'ب', 'م', 'ف' -> MouthViseme.CLOSED
            'و', 'ؤ' -> MouthViseme.ROUND
            'ي', 'ى', 'س', 'ش', 'ث', 'ز', 'ج' -> MouthViseme.WIDE
            'ا', 'أ', 'إ', 'آ', 'ع', 'ه', 'ح', 'خ', 'ق', 'ك' -> MouthViseme.OPEN
            else -> MouthViseme.OPEN
        }
    }

    private companion object {
        const val MAX_TTS_RETRIES = 2
        const val RETRY_BASE_DELAY_MS = 1_200L
    }
}
