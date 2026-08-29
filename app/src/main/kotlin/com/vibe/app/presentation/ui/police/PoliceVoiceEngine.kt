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
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        if (ttsReady) selectBestArabicVoice()
    }

    fun startListening() {
        if (mode == VoiceMode.OFFLINE && !hasOnDeviceRecognizer()) {
            listener.onSpeechError(
                "وضع بدون إنترنت يحتاج حزمة تعرّف صوتي محلية على الجهاز. ثبّت العربية للتعرّف دون اتصال أو اختر وضع الإنترنت.",
                false
            )
            return
        }

        if (mode == VoiceMode.ONLINE && !SpeechRecognizer.isRecognitionAvailable(context)) {
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
        if (!selectBestArabicVoice()) {
            listener.onTtsError(
                if (mode == VoiceMode.OFFLINE) {
                    "لا يوجد صوت عربي محلي مثبت. نزّل صوتاً عربياً من إعدادات تحويل النص إلى كلام أو اختر وضع الإنترنت."
                } else {
                    "لا يوجد صوت عربي متاح على الجهاز."
                }
            )
            return
        }

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

    private fun hasOnDeviceRecognizer(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun createRecognizer(): SpeechRecognizer {
        return if (mode == VoiceMode.OFFLINE && hasOnDeviceRecognizer()) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
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
                } else {
                    listener.onTtsError("لم يتم العثور على محرك نطق جاهز.")
                }
            } else {
                listener.onTtsError("لم يتم العثور على محرك نطق جاهز.")
            }
        }
    }

    /**
     * Returns true only when the selected mode can actually speak Arabic.
     * OFFLINE never silently falls back to a network-required voice.
     */
    private fun selectBestArabicVoice(): Boolean {
        val engine = tts ?: return false
        if (!ttsReady) return false

        val arabicVoices = engine.voices
            ?.filter { it.locale.language.equals("ar", ignoreCase = true) }
            .orEmpty()

        if (arabicVoices.isEmpty()) {
            return engine.isLanguageAvailable(Locale("ar", "SA")) >= TextToSpeech.LANG_AVAILABLE &&
                mode == VoiceMode.ONLINE
        }

        val candidates = if (mode == VoiceMode.OFFLINE) {
            arabicVoices.filterNot { it.isNetworkConnectionRequired }
        } else {
            arabicVoices
        }

        val preferred = candidates.maxWithOrNull(
            compareBy<android.speech.tts.Voice> { it.quality }
                .thenBy { -it.latency }
        ) ?: return false

        engine.voice = preferred
        return true
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener.onReadyToListen()
        }

        override fun onBeginningOfSpeech() {
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
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "تعذر الوصول لخدمة التعرف على الصوت."
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
