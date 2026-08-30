package com.malik.alshurti

import android.content.Context
import com.malik.alshurti.neural.NeuralArabicVoice
import com.malik.alshurti.stt.LocalArabicRecognizer

class PoliceVoiceEngine(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onSpeechPreparing(percent: Int, message: String)
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

    private val localRecognizer = LocalArabicRecognizer(
        context = context.applicationContext,
        callbacks = object : LocalArabicRecognizer.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                listener.onSpeechPreparing(percent, message)
            }

            override fun onReady() = Unit

            override fun onListening() {
                listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                listener.onSpeechStarted()
            }

            override fun onPartial(text: String) {
                listener.onPartialText(text)
            }

            override fun onFinal(text: String) {
                listener.onFinalText(text)
            }

            override fun onError(message: String, recoverable: Boolean) {
                listener.onSpeechError(message, recoverable)
            }
        }
    )

    private val neuralVoice = NeuralArabicVoice(
        context = context.applicationContext,
        callbacks = object : NeuralArabicVoice.Callbacks {
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
        val allowDownload = newMode == VoiceMode.ONLINE
        localRecognizer.prepare(allowDownload = allowDownload)
        neuralVoice.prepare(allowDownload = allowDownload)
    }

    fun startListening() {
        localRecognizer.startListening()
    }

    fun stopListening() {
        localRecognizer.stop()
    }

    fun interruptSpeech() {
        neuralVoice.interrupt()
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
        neuralVoice.speak(spokenText)
    }

    fun release() {
        stopListening()
        localRecognizer.release()
        neuralVoice.release()
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
