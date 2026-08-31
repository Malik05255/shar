package com.malik.alshurti

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.malik.alshurti.voice.GeminiSilentListener
import com.malik.alshurti.voice.SaudiHumanVoice

/**
 * Half-duplex voice coordinator.
 *
 * Input uses AudioRecord through GeminiSilentListener instead of Android SpeechRecognizer, so OEM
 * start/stop recording tones are not part of the conversation path. Transient Gemini TTS failures
 * are retried automatically because the production screen intentionally has no diagnostic text.
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mode: VoiceMode = VoiceMode.ONLINE
    private var spokenText = ""
    private var lastViseme = MouthViseme.REST
    private var observerHasSpoken = false
    private var ttsRetryCount = 0
    private var released = false

    private val silentListener = GeminiSilentListener(
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

    private val saudiVoice = SaudiHumanVoice(
        context = context.applicationContext,
        callbacks = object : SaudiHumanVoice.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (!observerHasSpoken) listener.onTtsPreparing(percent, message)
            }

            override fun onReady() {
                listener.onTtsReady()
            }

            override fun onSpeechStarted(durationMs: Long) {
                ttsRetryCount = 0
                lastViseme = MouthViseme.OPEN
                listener.onViseme(lastViseme)
                listener.onTtsStarted()
            }

            override fun onSpeechCursor(fraction: Float) {
                val viseme = visemeAtFraction(spokenText, fraction)
                if (viseme != lastViseme) {
                    lastViseme = viseme
                    listener.onViseme(viseme)
                }
            }

            override fun onSpeechFinished() {
                ttsRetryCount = 0
                lastViseme = MouthViseme.REST
                listener.onViseme(MouthViseme.REST)
                listener.onTtsFinished()
            }

            override fun onError(message: String) {
                lastViseme = MouthViseme.REST
                listener.onViseme(MouthViseme.REST)
                handleVoiceError(message)
            }
        }
    )

    fun setMode(newMode: VoiceMode) {
        mode = newMode
        stopListening()
        observerHasSpoken = false
        ttsRetryCount = 0
        if (newMode == VoiceMode.OFFLINE) {
            listener.onTtsError("الصوت السعودي البشري يحتاج اتصالاً بالإنترنت.")
            return
        }
        saudiVoice.prepare()
    }

    fun startListening() {
        if (mode != VoiceMode.ONLINE) {
            listener.onSpeechError("المحادثة الصوتية تعمل في وضع الإنترنت فقط.", false)
            return
        }
        silentListener.start()
    }

    fun stopListening() {
        silentListener.stop()
    }

    fun interruptSpeech() {
        ttsRetryCount = 0
        mainHandler.removeCallbacksAndMessages(null)
        saudiVoice.interrupt()
        lastViseme = MouthViseme.REST
        listener.onViseme(MouthViseme.REST)
    }

    fun speak(text: String) {
        spokenText = text.trim()
        ttsRetryCount = 0

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
        saudiVoice.speak(spokenText)
    }

    fun release() {
        released = true
        ttsRetryCount = 0
        mainHandler.removeCallbacksAndMessages(null)
        stopListening()
        silentListener.release()
        saudiVoice.release()
        lastViseme = MouthViseme.REST
        listener.onViseme(MouthViseme.REST)
    }

    private fun handleVoiceError(message: String) {
        if (shouldRetryVoice(message) && ttsRetryCount < MAX_TTS_RETRIES && spokenText.isNotBlank()) {
            val retryNumber = ++ttsRetryCount
            val retryText = spokenText
            mainHandler.postDelayed({ retryCurrentSpeech(retryText) }, RETRY_BASE_DELAY_MS * retryNumber)
        } else {
            listener.onTtsError(message)
        }
    }

    private fun retryCurrentSpeech(expectedText: String) {
        if (released || mode != VoiceMode.ONLINE || expectedText != spokenText || expectedText.isBlank()) return
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
