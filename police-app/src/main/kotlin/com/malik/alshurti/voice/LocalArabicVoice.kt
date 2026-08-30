package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import ai.runanywhere.proto.v1.ModelCategory
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.downloadModel
import com.runanywhere.sdk.public.extensions.loadModel
import com.runanywhere.sdk.public.extensions.registerModel
import com.runanywhere.sdk.public.extensions.synthesize
import com.runanywhere.sdk.public.types.RAInferenceFramework
import com.runanywhere.sdk.public.types.RAModelLoadRequest
import com.runanywhere.sdk.public.types.RATTSOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Fully local Arabic neural TTS using the Sherpa-ONNX runtime.
 *
 * The selected voice is a high-quality Arabic Piper/VITS model. The model is downloaded
 * once when network provisioning is allowed; subsequent synthesis runs on the phone and
 * does not consume API minutes or depend on a cloud service.
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val preparing = AtomicBoolean(false)

    @Volatile private var ready = false
    @Volatile private var released = false
    @Volatile private var player: MediaPlayer? = null
    private var cursorJob: Job? = null

    fun prepare(allowDownload: Boolean) {
        if (released) return
        if (ready) {
            callbacks.onReady()
            return
        }
        if (!preparing.compareAndSet(false, true)) return

        scope.launch {
            try {
                registerVoiceModel()
                if (allowDownload) {
                    postPreparing(8, "جاري تجهيز الصوت العربي المحلي لأول مرة…")
                    RunAnywhere.downloadModel(MODEL_ID).collect {
                        postPreparing(35, "جاري تنزيل الصوت العربي المحلي…")
                    }
                } else {
                    postPreparing(15, "جاري فتح الصوت العربي المحفوظ…")
                }

                val load = RunAnywhere.loadModel(
                    RAModelLoadRequest(
                        model_id = MODEL_ID,
                        category = ModelCategory.MODEL_CATEGORY_SPEECH_SYNTHESIS
                    )
                )
                check(load.success) {
                    if (allowDownload) {
                        "تعذر تحميل الصوت العربي المحلي: ${load.error_message}"
                    } else {
                        "الصوت العربي المحلي غير مثبت بعد. شغّل وضع الإنترنت مرة واحدة لتثبيته."
                    }
                }

                ready = true
                preparing.set(false)
                main.post {
                    callbacks.onPreparing(100, "الصوت العربي المحلي جاهز")
                    callbacks.onReady()
                }
            } catch (t: Throwable) {
                preparing.set(false)
                ready = false
                main.post {
                    callbacks.onError(
                        t.message ?: if (allowDownload) {
                            "تعذر تجهيز الصوت العربي المحلي."
                        } else {
                            "الصوت العربي المحلي غير مثبت."
                        }
                    )
                }
            }
        }
    }

    fun speak(text: String) {
        val clean = normalize(text)
        if (clean.isBlank()) {
            callbacks.onSpeechFinished()
            return
        }
        if (!ready) {
            callbacks.onError("الصوت العربي المحلي غير جاهز بعد.")
            return
        }

        val ticket = generation.incrementAndGet()
        stopPlayer()
        postPreparing(5, "جاري توليد الصوت على الجهاز…")

        scope.launch {
            try {
                val output = RunAnywhere.synthesize(
                    clean.take(MAX_TEXT_CHARS),
                    RATTSOptions()
                )
                if (ticket != generation.get() || released) return@launch
                val bytes = output.audio_data.toByteArray()
                check(bytes.size > MIN_WAV_BYTES) { "المحرك المحلي لم يرجع ملف صوت صالح." }

                val wav = File.createTempFile("alshorti-local-voice-", ".wav", appContext.cacheDir)
                wav.outputStream().use { it.write(bytes) }
                main.post {
                    callbacks.onPreparing(95, "تم توليد الصوت — جاري تشغيله…")
                    play(ticket, wav)
                }
            } catch (t: Throwable) {
                if (ticket == generation.get() && !released) {
                    main.post { callbacks.onError(t.message ?: "تعذر توليد الصوت العربي المحلي.") }
                }
            }
        }
    }

    fun interrupt() {
        generation.incrementAndGet()
        main.post { stopPlayer() }
    }

    fun release() {
        released = true
        generation.incrementAndGet()
        main.post { stopPlayer() }
        scope.cancel()
    }

    private fun registerVoiceModel() {
        if (!modelRegistered.compareAndSet(false, true)) return
        RunAnywhere.registerModel(
            id = MODEL_ID,
            name = "Arabic Jordan/Saudi Dii High",
            url = MODEL_URL,
            framework = RAInferenceFramework.INFERENCE_FRAMEWORK_ONNX,
            modality = ModelCategory.MODEL_CATEGORY_SPEECH_SYNTHESIS,
            memoryRequirement = MODEL_MEMORY_REQUIREMENT
        )
    }

    private fun play(ticket: Long, file: File) {
        if (ticket != generation.get() || released) {
            file.delete()
            return
        }
        stopPlayer()

        val active = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnPreparedListener { prepared ->
                if (ticket != generation.get() || released) {
                    runCatching { prepared.release() }
                    file.delete()
                    return@setOnPreparedListener
                }
                val duration = prepared.duration.coerceAtLeast(1)
                callbacks.onSpeechStarted(duration.toLong())
                prepared.start()
                startCursor(ticket, prepared, duration)
            }
            setOnCompletionListener { completed ->
                if (player === completed) player = null
                cursorJob?.cancel()
                cursorJob = null
                runCatching { completed.release() }
                file.delete()
                if (ticket == generation.get() && !released) callbacks.onSpeechFinished()
            }
            setOnErrorListener { failed, _, _ ->
                if (player === failed) player = null
                cursorJob?.cancel()
                cursorJob = null
                runCatching { failed.release() }
                file.delete()
                if (ticket == generation.get() && !released) {
                    callbacks.onError("تعذر تشغيل الصوت العربي المحلي.")
                }
                true
            }
        }
        player = active
        active.prepareAsync()
    }

    private fun startCursor(ticket: Long, active: MediaPlayer, durationMs: Int) {
        cursorJob?.cancel()
        cursorJob = scope.launch(Dispatchers.Main) {
            while (ticket == generation.get() && player === active && !released) {
                val current = runCatching { active.currentPosition }.getOrDefault(0)
                callbacks.onSpeechCursor((current.toFloat() / durationMs).coerceIn(0f, 1f))
                kotlinx.coroutines.delay(CURSOR_INTERVAL_MS)
            }
        }
    }

    private fun stopPlayer() {
        cursorJob?.cancel()
        cursorJob = null
        val active = player ?: return
        player = null
        runCatching { active.stop() }
        runCatching { active.reset() }
        runCatching { active.release() }
    }

    private fun postPreparing(percent: Int, message: String) {
        main.post { callbacks.onPreparing(percent, message) }
    }

    private fun normalize(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace("…", "،")
        .trim()

    private companion object {
        const val MODEL_ID = "vits-piper-ar_JO-SA_dii-high"
        const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ar_JO-SA_dii-high.tar.bz2"
        const val MODEL_MEMORY_REQUIREMENT = 180_000_000L
        const val MAX_TEXT_CHARS = 260
        const val MIN_WAV_BYTES = 1_024
        const val CURSOR_INTERVAL_MS = 55L
        val modelRegistered = AtomicBoolean(false)
    }
}
