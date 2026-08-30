package com.malik.alshurti

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.malik.alshurti.voice.SaudiHumanVoice

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

    private var speechRecognizer: SpeechRecognizer? = null
    private var mode: VoiceMode = VoiceMode.ONLINE
    private var listening = false
    private var spokenText = ""
    private var lastViseme = MouthViseme.REST

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
        destroyRecognizer()

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

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onSpeechError("التعرّف على الصوت غير متوفر على هذا الجهاز.", false)
            return
        }

        stopListening()
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            speechRecognizer = it
            it.setRecognitionListener(recognitionListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

            // Saudi conversational speech often contains natural pauses between phrases. The old
            // 650 ms cutoff truncated users mid-sentence; these values allow a human pause without
            // making the turn feel sluggish after the speaker actually finishes.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_150L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 720L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 450L)
        }

        try {
            listening = true
            recognizer.startListening(intent)
        } catch (t: Throwable) {
            listening = false
            destroyRecognizer()
            listener.onSpeechError(t.message ?: "تعذر تشغيل الميكروفون.", true)
        }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { speechRecognizer?.cancel() }
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
        destroyRecognizer()
        saudiVoice.release()
        lastViseme = MouthViseme.REST
        listener.onViseme(MouthViseme.REST)
    }

    private fun destroyRecognizer() {
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
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

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onReadyToListen()
        override fun onBeginningOfSpeech() = listener.onSpeechStarted()
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onError(error: Int) {
            listening = false

            // Busy/client failures frequently leave OEM recognizers poisoned for the next call.
            // Drop that instance so the automatic retry gets a genuinely fresh recognizer.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                destroyRecognizer()
            }

            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "مشكلة في صوت الميكروفون."
                SpeechRecognizer.ERROR_CLIENT -> "توقف الاستماع مؤقتاً."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "يحتاج التطبيق إذن الميكروفون."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "تعذر الوصول لخدمة التعرف على الصوت."
                SpeechRecognizer.ERROR_NO_MATCH -> "ما سمعت الكلام بوضوح."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "الميكروفون مشغول، سأحاول مرة ثانية."
                SpeechRecognizer.ERROR_SERVER -> "خدمة التعرف على الصوت غير متاحة الآن."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "تكلم متى ما كنت جاهز."
                else -> "تعذر فهم الصوت ($error)."
            }
            listener.onSpeechError(message, recoverable)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            listener.onFinalText(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) listener.onPartialText(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
