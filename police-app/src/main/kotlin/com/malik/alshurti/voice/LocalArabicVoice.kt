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
import com.runanywhere.sdk.public.types.RAModelInfo
import com.runanywhere.sdk.public.types.RAModelLoadRequest
import com.runanywhere.sdk.public.types.RATTSOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Fully local Arabic neural TTS using the Sherpa-ONNX runtime.
 *
 * The model is downloaded once when ONLINE provisioning is explicitly allowed.
 * Synthesis after that runs on the phone without metered API minutes.
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
    @Volatile private var registeredModel: RAModelInfo? = null
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
                val modelInfo = registerVoiceModel()

                if (allowDownload) {
                    postPreparing(8, "جاري تجهيز الصوت العربي المحلي لأول مرة…")
                    RunAnywhere.downloadModel(modelInfo) {
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

                // Sherpa output is raw Float32 PCM. MediaPlayer needs a WAV container.
                val floatPcm = output.audio_data.toByteArray()
                check(floatPcm.size >= MIN_FLOAT_PCM_BYTES) {
                    "المحرك المحلي لم يرجع صوتاً صالحاً."
                }
                val sampleRate = output.sample_rate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                val wavBytes = float32PcmToWav(floatPcm, sampleRate)

                val wav = File.createTempFile("alshorti-local-voice-", ".wav", appContext.cacheDir)
                wav.outputStream().use { outputStream ->
                    outputStream.write(wavBytes)
                    outputStream.flush()
                }
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

    private suspend fun registerVoiceModel(): RAModelInfo {
        registeredModel?.let { return it }
        val model = RunAnywhere.registerModel(
            id = MODEL_ID,
            name = "Arabic Saudi Miro V2 High",
            url = MODEL_URL,
            framework = RAInferenceFramework.INFERENCE_FRAMEWORK_ONNX,
            modality = ModelCategory.MODEL_CATEGORY_SPEECH_SYNTHESIS,
            memoryRequirement = MODEL_MEMORY_REQUIREMENT
        )
        registeredModel = model
        return model
    }

    private fun float32PcmToWav(floatPcm: ByteArray, sampleRate: Int): ByteArray {
        val sampleCount = floatPcm.size / 4
        val pcm16 = ByteArray(sampleCount * 2)
        var source = 0
        var target = 0
        repeat(sampleCount) {
            val bits =
                (floatPcm[source].toInt() and 0xff) or
                    ((floatPcm[source + 1].toInt() and 0xff) shl 8) or
                    ((floatPcm[source + 2].toInt() and 0xff) shl 16) or
                    ((floatPcm[source + 3].toInt() and 0xff) shl 24)
            val sample = Float.fromBits(bits).coerceIn(-1f, 1f)
            val value = (sample * 32767f).toInt().coerceIn(-32768, 32767)
            pcm16[target] = (value and 0xff).toByte()
            pcm16[target + 1] = ((value ushr 8) and 0xff).toByte()
            source += 4
            target += 2
        }

        val buffer = ByteArrayOutputStream(44 + pcm16.size)
        DataOutputStream(buffer).use { out ->
            fun ascii(value: String) = out.writeBytes(value)
            fun intLE(value: Int) {
                out.writeByte(value and 0xff)
                out.writeByte((value ushr 8) and 0xff)
                out.writeByte((value ushr 16) and 0xff)
                out.writeByte((value ushr 24) and 0xff)
            }
            fun shortLE(value: Int) {
                out.writeByte(value and 0xff)
                out.writeByte((value ushr 8) and 0xff)
            }

            ascii("RIFF")
            intLE(36 + pcm16.size)
            ascii("WAVE")
            ascii("fmt ")
            intLE(16)
            shortLE(1)
            shortLE(1)
            intLE(sampleRate)
            intLE(sampleRate * 2)
            shortLE(2)
            shortLE(16)
            ascii("data")
            intLE(pcm16.size)
            out.write(pcm16)
        }
        return buffer.toByteArray()
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
        const val MODEL_ID = "vits-piper-ar_JO-SA_miro_V2-high"
        const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ar_JO-SA_miro_V2-high.tar.bz2"
        const val MODEL_MEMORY_REQUIREMENT = 180_000_000L
        const val MAX_TEXT_CHARS = 260
        const val MIN_FLOAT_PCM_BYTES = 4_096
        const val DEFAULT_SAMPLE_RATE = 22_050
        const val CURSOR_INTERVAL_MS = 55L
    }
}
