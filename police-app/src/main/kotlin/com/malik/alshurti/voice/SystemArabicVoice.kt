package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Last-resort free Android TTS backend.
 *
 * This backend is intentionally independent from Supertonic and Gemini. It uses the TTS engine
 * already installed on the phone, so a device-specific AudioTrack problem or a missing API key can
 * never turn a valid police reply into silent failure without one more independent playback path.
 */
class SystemArabicVoice(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPreparing(message: String)
        fun onReady()
        fun onSpeechStarted(durationMs: Long)
        fun onSpeechCursor(fraction: Float)
        fun onSpeechFinished()
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)

    @Volatile private var ready = false
    @Volatile private var released = false
    @Volatile private var initializing = false
    @Volatile private var currentUtteranceId: String? = null
    @Volatile private var currentDurationMs = 0L
    @Volatile private var startedAtMs = 0L

    private var tts: TextToSpeech? = null
    private val cursorRunnable = object : Runnable {
        override fun run() {
            if (released) return
            val utteranceId = currentUtteranceId ?: return
            val duration = currentDurationMs.coerceAtLeast(1L)
            val elapsed = (android.os.SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
            callbacks.onSpeechCursor((elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 0.98f))
            if (currentUtteranceId == utteranceId) mainHandler.postDelayed(this, CURSOR_INTERVAL_MS)
        }
    }

    /** Start Android TTS only after the owner has completed its own construction. */
    fun prepare() {
        if (released) return
        if (ready) {
            callbacks.onReady()
            return
        }
        if (initializing) return
        initializing = true
        callbacks.onPreparing("جاري تجهيز صوت النظام…")
        tts = TextToSpeech(appContext) { status ->
            if (released) return@TextToSpeech
            initializing = false
            if (status != TextToSpeech.SUCCESS) {
                callbacks.onError("تعذر تشغيل محرك الصوت الموجود على الجهاز.")
                return@TextToSpeech
            }
            configureEngine()
        }
    }

    fun speak(text: String) {
        val engine = tts
        if (released || !ready || engine == null) {
            callbacks.onError("صوت النظام لم يجهز بعد.")
            return
        }
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            callbacks.onSpeechFinished()
            return
        }

        interrupt()
        val ticket = generation.incrementAndGet()
        val utteranceId = "alshorti-system-$ticket"
        currentUtteranceId = utteranceId
        currentDurationMs = estimateDurationMs(normalized)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = engine.speak(normalized, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            currentUtteranceId = null
            callbacks.onError("تعذر إرسال الكلام إلى صوت النظام.")
        }
    }

    fun interrupt() {
        generation.incrementAndGet()
        currentUtteranceId = null
        mainHandler.removeCallbacks(cursorRunnable)
        runCatching { tts?.stop() }
    }

    fun release() {
        if (released) return
        released = true
        interrupt()
        ready = false
        initializing = false
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun configureEngine() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(0.92f)
        engine.setPitch(0.98f)

        val saudi = Locale("ar", "SA")
        val languageResult = engine.setLanguage(saudi)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            val genericResult = engine.setLanguage(Locale("ar"))
            if (genericResult == TextToSpeech.LANG_MISSING_DATA ||
                genericResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                callbacks.onError("لا يوجد صوت عربي مثبت في محرك النظام.")
                return
            }
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == null || utteranceId != currentUtteranceId || released) return
                startedAtMs = android.os.SystemClock.elapsedRealtime()
                mainHandler.post {
                    callbacks.onSpeechStarted(currentDurationMs)
                    mainHandler.removeCallbacks(cursorRunnable)
                    mainHandler.post(cursorRunnable)
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null || utteranceId != currentUtteranceId || released) return
                currentUtteranceId = null
                mainHandler.post {
                    mainHandler.removeCallbacks(cursorRunnable)
                    callbacks.onSpeechCursor(1f)
                    callbacks.onSpeechFinished()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleUtteranceError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleUtteranceError(utteranceId)
            }
        })

        ready = true
        callbacks.onReady()
    }

    private fun handleUtteranceError(utteranceId: String?) {
        if (utteranceId == null || utteranceId != currentUtteranceId || released) return
        currentUtteranceId = null
        mainHandler.post {
            mainHandler.removeCallbacks(cursorRunnable)
            callbacks.onError("فشل محرك الصوت الموجود على الجهاز أثناء النطق.")
        }
    }

    private fun estimateDurationMs(text: String): Long {
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val byWords = words * 410L
        val byChars = text.length * 67L
        return maxOf(850L, byWords, byChars).coerceAtMost(45_000L)
    }

    private companion object {
        const val CURSOR_INTERVAL_MS = 65L
    }
}
