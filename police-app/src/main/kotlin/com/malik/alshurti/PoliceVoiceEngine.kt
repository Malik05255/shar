package com.malik.alshurti

import android.content.Context
import com.malik.alshurti.voice.GeminiSilentListener
import com.malik.alshurti.voice.SaudiHumanVoice

/**
 * Half-duplex voice coordinator.
 *
 * Input deliberately uses AudioRecord through GeminiSilentListener instead of Android's
 * SpeechRecognizer. That removes OEM recording beeps/restart tones and keeps the microphone quiet
 * while the character waits for the child to speak.
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

    private var mode: VoiceMode = VoiceMode.ONLINE
    private var spokenText = ""
    private var lastViseme = MouthViseme.REST

    private val silentListener = GeminiSilentListener(
        context = context.applicationContext,
        callbacks = object : GeminiSilentListener.Callbacks {
            override fun onReady() {
                listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                listener.onSpeechStarted()
            }

            override fun onFinalText(text: String) {
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
                listener.onTtsPreparing(percent, message)
            }

            override fun onReady() {
                listener.onTtsReady()
            }

            override fun onSpeechStarted(durationMs: Long) {
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
                lastViseme = MouthViseme.REST
                listener.onViseme(MouthViseme.REST)
                listener.onTtsFinished()
            }

            override fun onError(message: String) {
                lastViseme = MouthViseme.REST
                listener.onViseme(MouthViseme.REST)
                listener.onTtsError(message)
            }
        }
    )

    fun setMode(newMode: VoiceMode) {
        mode = newMode
        stopListening()
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
        saudiVoice.interrupt()
        lastViseme = MouthViseme.REST
        listener.onViseme(MouthViseme.REST)
    }

    fun speak(text: String) {
        stopListening()
        spokenText = text.trim()
        if (spokenText.isBlank()) {
            listener.onTtsFinished()
            return
        }
        saudiVoice.speak(spokenText)
    }

    fun release() {
        stopListening()
        silentListener.release()
        saudiVoice.release()
        lastViseme = MouthViseme.REST
        listener.onViseme(MouthViseme.REST)
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
}
