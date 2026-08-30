package com.malik.alshurti.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Stable Arabic voice layer backed by the device TTS engine.
 *
 * We intentionally do not ship a Piper/VITS model here. On phones that expose a
 * high-quality Arabic network voice (Google/Samsung/etc.), ONLINE mode prefers it.
 * OFFLINE mode only considers voices that do not require a network connection.
 *
 * This keeps startup instant, removes large native TTS runtimes, and lets Android
 * choose hardware/engine-specific neural voices where available.
 */
class LocalArabicVoice(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPreparing(percent: Int, message: String)
        fun onReady()
        fun onSpeechStarted(durationMs: Long)
        fun onSpeechCursor(fraction: Float)
        fun onSpeechFinished()
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val preparing = AtomicBoolean(false)

    @Volatile private var released = false
    @Volatile private var initialized = false
    @Volatile private var ready = false
    @Volatile private var configuredNetworkPolicy: Boolean? = null
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var activeUtteranceId: String? = null
    @Volatile private var activeText: String = ""

    fun prepare(allowDownload: Boolean) {
        if (released) return

        val current = engine
        if (initialized && current != null) {
            configureVoice(current, allowDownload)
            return
        }
        if (!preparing.compareAndSet(false, true)) return

        postPreparing(15, "جاري اختيار أفضل صوت عربي…")
        var created: TextToSpeech? = null
        created = TextToSpeech(appContext) { status ->
            if (released) {
                runCatching { created?.shutdown() }
                return@TextToSpeech
            }
            preparing.set(false)
            if (status != TextToSpeech.SUCCESS || created == null) {
                initialized = false
                ready = false
                main.post { callbacks.onError("تعذر تشغيل محرك الصوت في الجهاز.") }
                return@TextToSpeech
            }

            engine = created
            initialized = true
            installProgressListener(created!!)
            configureVoice(created!!, allowDownload)
        }
    }

    fun speak(text: String) {
        val clean = normalize(text)
        if (clean.isBlank()) {
            main.post(callbacks::onSpeechFinished)
            return
        }

        val tts = engine
        if (!ready || tts == null) {
            main.post { callbacks.onError("الصوت العربي غير جاهز. جرّب مرة ثانية.") }
            return
        }

        val ticket = generation.incrementAndGet()
        val utteranceId = "alshorti-$ticket"
        activeUtteranceId = utteranceId
        activeText = clean.take(MAX_TEXT_CHARS)
        callbacks.onPreparing(95, "الشرطي يجهز الرد…")

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = tts.speak(activeText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            activeUtteranceId = null
            main.post { callbacks.onError("تعذر تشغيل الصوت العربي المختار.") }
        }
    }

    fun interrupt() {
        generation.incrementAndGet()
        activeUtteranceId = null
        runCatching { engine?.stop() }
    }

    fun release() {
        released = true
        generation.incrementAndGet()
        activeUtteranceId = null
        ready = false
        initialized = false
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
    }

    private fun configureVoice(tts: TextToSpeech, allowNetwork: Boolean) {
        if (released) return
        postPreparing(35, if (allowNetwork) "اختيار أفضل صوت عربي متاح…" else "اختيار أفضل صوت عربي بدون إنترنت…")

        val selected = chooseBestArabicVoice(tts, allowNetwork)
        if (selected != null) {
            val voiceResult = runCatching { tts.setVoice(selected) }.getOrDefault(TextToSpeech.ERROR)
            if (voiceResult == TextToSpeech.SUCCESS) {
                applyNaturalProsody(tts)
                ready = true
                configuredNetworkPolicy = allowNetwork
                main.post {
                    callbacks.onPreparing(100, "الصوت العربي جاهز")
                    callbacks.onReady()
                }
                return
            }
        }

        // A few engines do not publish Voice metadata but still support Arabic.
        val fallbackLocale = Locale("ar", "SA")
        val languageResult = runCatching { tts.setLanguage(fallbackLocale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (languageResult != TextToSpeech.LANG_MISSING_DATA && languageResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            applyNaturalProsody(tts)
            ready = true
            configuredNetworkPolicy = allowNetwork
            main.post {
                callbacks.onPreparing(100, "الصوت العربي جاهز")
                callbacks.onReady()
            }
            return
        }

        ready = false
        val message = if (allowNetwork) {
            "ما لقيت في جهازك صوت عربي مناسب. ثبّت أو فعّل خدمة تحويل النص إلى كلام العربية في إعدادات الجهاز."
        } else {
            "ما فيه صوت عربي محفوظ للعمل بدون إنترنت. اختر الإنترنت أو نزّل صوتاً عربياً من إعدادات تحويل النص إلى كلام."
        }
        main.post { callbacks.onError(message) }
    }

    private fun chooseBestArabicVoice(tts: TextToSpeech, allowNetwork: Boolean): Voice? {
        val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        return voices
            .asSequence()
            .filter { it.locale?.language.equals("ar", ignoreCase = true) }
            .filter { allowNetwork || !it.isNetworkConnectionRequired }
            .maxByOrNull { scoreVoice(it, allowNetwork) }
    }

    private fun scoreVoice(voice: Voice, allowNetwork: Boolean): Int {
        val country = voice.locale?.country.orEmpty().uppercase(Locale.ROOT)
        val gulfBonus = when (country) {
            "SA" -> 6_000
            "AE", "KW", "QA", "BH", "OM" -> 3_500
            else -> 0
        }
        val qualityScore = voice.quality.coerceAtLeast(0) * 20
        val latencyScore = (Voice.LATENCY_VERY_HIGH - voice.latency).coerceAtLeast(0)
        val networkBonus = if (allowNetwork && voice.isNetworkConnectionRequired) 450 else 0
        val name = voice.name.orEmpty().lowercase(Locale.ROOT)
        val localeHint = when {
            "ar-sa" in name || "ar_sa" in name || "saudi" in name -> 1_200
            "arab" in name -> 200
            else -> 0
        }
        return gulfBonus + qualityScore + latencyScore + networkBonus + localeHint
    }

    private fun applyNaturalProsody(tts: TextToSpeech) {
        // Slightly slower than many OEM defaults improves Arabic articulation without
        // creating the exaggerated "robot reading" cadence of slow synthetic voices.
        tts.setSpeechRate(0.96f)
        tts.setPitch(0.98f)
    }

    private fun installProgressListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!isActive(utteranceId)) return
                val estimate = estimateDurationMs(activeText)
                main.post {
                    callbacks.onSpeechCursor(0f)
                    callbacks.onSpeechStarted(estimate)
                }
            }

            override fun onDone(utteranceId: String?) {
                if (!isActive(utteranceId)) return
                activeUtteranceId = null
                main.post {
                    callbacks.onSpeechCursor(1f)
                    callbacks.onSpeechFinished()
                }
            }

            override fun onError(utteranceId: String?) {
                onTtsError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onTtsError(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == activeUtteranceId) activeUtteranceId = null
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (!isActive(utteranceId)) return
                val length = activeText.length.coerceAtLeast(1)
                val fraction = start.toFloat().div(length).coerceIn(0f, 1f)
                main.post { callbacks.onSpeechCursor(fraction) }
            }
        })
    }

    private fun onTtsError(utteranceId: String?) {
        if (!isActive(utteranceId)) return
        activeUtteranceId = null
        main.post { callbacks.onError("فشل محرك الصوت في نطق الرد. جرّب تغيير وضع الإنترنت من القائمة.") }
    }

    private fun isActive(utteranceId: String?): Boolean =
        !released && utteranceId != null && utteranceId == activeUtteranceId

    private fun estimateDurationMs(text: String): Long =
        (text.length * 62L).coerceIn(850L, 14_000L)

    private fun postPreparing(percent: Int, message: String) {
        main.post { callbacks.onPreparing(percent, message) }
    }

    private fun normalize(value: String): String = value
        .replace('ـ', ' ')
        .replace("…", "،")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val MAX_TEXT_CHARS = 320
    }
}
