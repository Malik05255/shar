package com.vibe.app.presentation.ui.police

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

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
        fun onTtsReady()
        fun onTtsStarted()
        fun onTtsFinished()
        fun onTtsError(message: String)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var mode: VoiceMode = VoiceMode.ONLINE
    private var ttsReady = false
    private var listening = false

    init {
        initTts()
    }

    fun setMode(newMode: VoiceMode) {
        if (mode == newMode) return
        mode = newMode
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        selectBestArabicVoice()
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onSpeechError("التعرّف على الصوت غير متوفر على هذا الجهاز.", false)
            return
        }

        stopListening()
        val recognizer = speechRecognizer ?: createRecognizer().also {
            speechRecognizer = it
            it.setRecognitionListener(recognitionListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, mode == VoiceMode.OFFLINE)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 420L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }

        try {
            listening = true
            recognizer.startListening(intent)
        } catch (t: Throwable) {
            listening = false
            listener.onSpeechError(t.message ?: "تعذر تشغيل الميكروفون.", true)
        }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { speechRecognizer?.stopListening() }
        runCatching { speechRecognizer?.cancel() }
    }

    fun interruptSpeech() {
        runCatching { tts?.stop() }
    }

    fun speak(text: String) {
        val engine = tts
        if (!ttsReady || engine == null) {
            listener.onTtsError("محرك الصوت العربي لم يجهز بعد.")
            return
        }

        stopListening()
        selectBestArabicVoice()
        engine.setSpeechRate(0.98f)
        engine.setPitch(0.96f)
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            listener.onTtsError("تعذر تشغيل صوت الشرطي.")
        }
    }

    fun release() {
        stopListening()
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private fun createRecognizer(): SpeechRecognizer {
        return if (
            mode == VoiceMode.OFFLINE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                listener.onTtsError("لم يتم العثور على محرك نطق جاهز.")
                return@TextToSpeech
            }

            val engine = tts ?: return@TextToSpeech
            engine.language = Locale("ar", "SA")
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    listener.onTtsStarted()
                }

                override fun onDone(utteranceId: String?) {
                    listener.onTtsFinished()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    listener.onTtsError("حدث خطأ أثناء نطق الرد.")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    listener.onTtsError("حدث خطأ أثناء نطق الرد ($errorCode).")
                }
            })
            ttsReady = true
            selectBestArabicVoice()
            listener.onTtsReady()
        }
    }

    private fun selectBestArabicVoice() {
        val engine = tts ?: return
        if (!ttsReady) return

        val arabicVoices = engine.voices
            ?.filter { it.locale.language.equals("ar", ignoreCase = true) }
            .orEmpty()

        if (arabicVoices.isEmpty()) {
            engine.language = Locale("ar", "SA")
            return
        }

        val preferred = arabicVoices
            .filter { voice ->
                if (mode == VoiceMode.OFFLINE) !voice.isNetworkConnectionRequired
                else voice.isNetworkConnectionRequired
            }
            .maxWithOrNull(compareBy<Voice> { it.quality }.thenByDescending { -it.latency })
            ?: arabicVoices.maxByOrNull { it.quality }

        preferred?.let { engine.voice = it }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener.onReadyToListen()
        }

        override fun onBeginningOfSpeech() {
            // Barge-in: any user speech immediately cancels the character voice.
            interruptSpeech()
            listener.onSpeechStarted()
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "مشكلة في صوت الميكروفون."
                SpeechRecognizer.ERROR_CLIENT -> "توقف الاستماع مؤقتاً."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "يحتاج التطبيق إذن الميكروفون."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "تعذر الوصول لخدمة التعرف على الصوت."
                SpeechRecognizer.ERROR_NO_MATCH -> "ما سمعت الكلام بوضوح."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "الميكروفون مشغول، سأحاول مرة ثانية."
                SpeechRecognizer.ERROR_SERVER -> "خدمة التعرف على الصوت غير متاحة الآن."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "أنا سامعك، تكلم متى ما كنت جاهز."
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

    private companion object {
        const val UTTERANCE_ID = "alshurti_reply"
    }
}
