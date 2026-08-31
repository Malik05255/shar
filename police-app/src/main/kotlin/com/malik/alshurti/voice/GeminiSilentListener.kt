package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.malik.alshurti.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Beep-free foreground speech capture.
 *
 * Android SpeechRecognizer is intentionally not used here because several OEM recognizers emit
 * start/stop tones and repeatedly restart after initial-silence timeouts. AudioRecord stays open
 * quietly, a lightweight local VAD finds one utterance, and only that utterance is sent to Gemini
 * 3.5 Transcribe. No microphone audio is stored on disk.
 */
class GeminiSilentListener(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onReady()
        fun onSpeechStarted()
        fun onFinalText(text: String)
        fun onError(message: String, recoverable: Boolean)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-silent-capture").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-transcribe").apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var released = false
    @Volatile private var listening = false

    fun start() {
        if (released || listening) return
        if (BuildConfig.GEMINI_API_KEY.trim().isBlank()) {
            dispatch { callbacks.onError("التعرّف الصوتي غير مهيأ في هذه النسخة.", false) }
            return
        }

        val ticket = generation.incrementAndGet()
        listening = true
        try {
            captureExecutor.execute {
                captureOneUtterance(ticket)
            }
        } catch (_: RejectedExecutionException) {
            listening = false
            dispatch { callbacks.onError("تعذر تشغيل الميكروفون.", true) }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        listening = false
        stopRecorder()
    }

    fun release() {
        released = true
        stop()
        runCatching { captureExecutor.shutdownNow() }
        runCatching { networkExecutor.shutdownNow() }
    }

    private fun captureOneUtterance(ticket: Long) {
        var localRecorder: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("الميكروفون غير متاح.")

            val bufferBytes = maxOf(minBuffer * 2, FRAME_SAMPLES * 2 * 8)
            localRecorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(INPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()

            if (localRecorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("تعذر تهيئة الميكروفون.")
            }
            if (ticket != generation.get() || released) return

            recorder = localRecorder
            localRecorder.startRecording()
            if (localRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("تعذر بدء الاستماع.")
            }
            dispatch { callbacks.onReady() }

            val frame = ShortArray(FRAME_SAMPLES)
            val preRoll = ArrayDeque<ShortArray>(PRE_ROLL_FRAMES + 1)
            val utterance = ArrayList<ShortArray>(240)
            var noiseFloor = INITIAL_NOISE_RMS
            var hotFrames = 0
            var speechStarted = false
            var frameIndex = 0
            var lastVoiceFrame = 0

            while (ticket == generation.get() && !released && listening) {
                val read = localRecorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                        throw IllegalStateException("انقطع الميكروفون مؤقتاً.")
                    }
                    continue
                }

                val current = if (read == frame.size) frame.copyOf() else frame.copyOf(read)
                val rms = rms(current)

                if (!speechStarted) {
                    noiseFloor = (noiseFloor * 0.965 + rms.coerceAtMost(NOISE_TRACKING_CEILING) * 0.035)
                        .coerceIn(MIN_NOISE_RMS, MAX_NOISE_RMS)
                    if (preRoll.size >= PRE_ROLL_FRAMES) preRoll.removeFirst()
                    preRoll.addLast(current)

                    val startThreshold = maxOf(ABSOLUTE_START_RMS, noiseFloor * START_NOISE_MULTIPLIER)
                    if (rms >= startThreshold) hotFrames += 1 else hotFrames = (hotFrames - 1).coerceAtLeast(0)

                    if (hotFrames >= START_CONFIRM_FRAMES) {
                        speechStarted = true
                        utterance.addAll(preRoll)
                        preRoll.clear()
                        lastVoiceFrame = frameIndex
                        dispatch { callbacks.onSpeechStarted() }
                    }
                } else {
                    utterance.add(current)
                    val continueThreshold = maxOf(ABSOLUTE_CONTINUE_RMS, noiseFloor * CONTINUE_NOISE_MULTIPLIER)
                    if (rms >= continueThreshold) lastVoiceFrame = frameIndex

                    val silenceFrames = frameIndex - lastVoiceFrame
                    val hasEnoughSpeech = utterance.size >= MIN_UTTERANCE_FRAMES
                    if ((hasEnoughSpeech && silenceFrames >= END_SILENCE_FRAMES) || utterance.size >= MAX_UTTERANCE_FRAMES) {
                        break
                    }
                }
                frameIndex += 1
            }

            if (ticket != generation.get() || released || !listening) return
            listening = false
            stopRecorderInstance(localRecorder)
            recorder = null
            localRecorder = null

            if (!speechStarted || utterance.size < MIN_UTTERANCE_FRAMES) {
                dispatch { callbacks.onError("ما سمعت كلاماً واضحاً.", true) }
                return
            }

            val wav = encodeWav(utterance)
            try {
                networkExecutor.execute {
                    transcribe(ticket, wav)
                }
            } catch (_: RejectedExecutionException) {
                dispatch { callbacks.onError("تعذر معالجة الصوت.", true) }
            }
        } catch (security: SecurityException) {
            listening = false
            dispatch { callbacks.onError("إذن الميكروفون مطلوب.", false) }
        } catch (t: Throwable) {
            listening = false
            if (ticket == generation.get() && !released) {
                dispatch { callbacks.onError(t.message ?: "تعذر تشغيل الميكروفون.", true) }
            }
        } finally {
            localRecorder?.let(::stopRecorderInstance)
            if (recorder === localRecorder) recorder = null
        }
    }

    private fun transcribe(ticket: Long, wav: ByteArray) {
        if (ticket != generation.get() || released) return
        val connection = (URL(INTERACTIONS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY.trim())
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Api-Revision", API_REVISION)
            setRequestProperty("User-Agent", "AlShorti-Android/${BuildConfig.VERSION_NAME}")
        }

        val audio = JSONObject()
            .put("type", "audio")
            .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
            .put("mime_type", "audio/wav")

        val transcriptionConfig = JSONObject()
            .put("language_codes", JSONArray().put("ar-SA"))
            .put("mode", "smart")

        val requestBody = JSONObject()
            .put("model", TRANSCRIBE_MODEL)
            .put("input", JSONArray().put(audio))
            .put("generation_config", JSONObject().put("transcription_config", transcriptionConfig))
            .toString()

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }

            if (ticket != generation.get() || released) return
            if (status !in 200..299) {
                val message = when (status) {
                    400 -> "خدمة تحويل الصوت رفضت التسجيل."
                    401 -> "مفتاح Gemini غير صالح."
                    403 -> "المفتاح لا يملك صلاحية تحويل الصوت."
                    404 -> "نموذج تحويل الصوت غير متاح."
                    429 -> "تم بلوغ حد استخدام تحويل الصوت مؤقتاً."
                    in 500..599 -> "خدمة تحويل الصوت غير متاحة مؤقتاً."
                    else -> "تعذر تحويل الصوت ($status)."
                }
                dispatch { callbacks.onError(message, status == 429 || status >= 500) }
                return
            }

            val text = extractOutputText(body).trim()
            if (text.isBlank()) {
                dispatch { callbacks.onError("ما قدرت أفهم الكلام بوضوح.", true) }
            } else {
                dispatch { callbacks.onFinalText(text) }
            }
        } catch (t: Throwable) {
            if (ticket == generation.get() && !released) {
                dispatch { callbacks.onError(t.message ?: "تعذر الوصول لخدمة تحويل الصوت.", true) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractOutputText(response: String): String {
        val root = JSONObject(response)
        val direct = root.optString("output_text", root.optString("outputText"))
        if (direct.isNotBlank()) return direct

        val collected = ArrayList<String>()
        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val block = content.optJSONObject(j) ?: continue
                    if (block.optString("type") == "text") {
                        block.optString("text").takeIf { it.isNotBlank() }?.let(collected::add)
                    }
                }
            }
        }
        return collected.joinToString(" ")
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var energy = 0.0
        samples.forEach { sample ->
            val value = sample.toDouble()
            energy += value * value
        }
        return sqrt(energy / samples.size.toDouble())
    }

    private fun encodeWav(frames: List<ShortArray>): ByteArray {
        val sampleCount = frames.sumOf { it.size }
        val dataBytes = sampleCount * 2
        val out = ByteArrayOutputStream(dataBytes + 44)

        fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun le16(value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
        }
        fun le32(value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 24) and 0xff)
        }

        ascii("RIFF")
        le32(dataBytes + 36)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1)
        le16(1)
        le32(INPUT_SAMPLE_RATE)
        le32(INPUT_SAMPLE_RATE * 2)
        le16(2)
        le16(16)
        ascii("data")
        le32(dataBytes)

        frames.forEach { frame ->
            frame.forEach { sample ->
                val value = sample.toInt()
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
            }
        }
        return out.toByteArray()
    }

    private fun stopRecorder() {
        val active = recorder
        recorder = null
        if (active != null) stopRecorderInstance(active)
    }

    private fun stopRecorderInstance(active: AudioRecord) {
        runCatching {
            if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
        }
        runCatching { active.release() }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) runCatching(block)
        else mainHandler.post { runCatching(block) }
    }

    private companion object {
        const val INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val API_REVISION = "2026-05-20"
        const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe"
        const val INPUT_SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = INPUT_SAMPLE_RATE * FRAME_MS / 1_000
        const val PRE_ROLL_FRAMES = 15
        const val START_CONFIRM_FRAMES = 3
        const val END_SILENCE_FRAMES = 34
        const val MIN_UTTERANCE_FRAMES = 18
        const val MAX_UTTERANCE_FRAMES = 750
        const val INITIAL_NOISE_RMS = 260.0
        const val MIN_NOISE_RMS = 90.0
        const val MAX_NOISE_RMS = 1_400.0
        const val NOISE_TRACKING_CEILING = 2_200.0
        const val ABSOLUTE_START_RMS = 720.0
        const val ABSOLUTE_CONTINUE_RMS = 420.0
        const val START_NOISE_MULTIPLIER = 2.7
        const val CONTINUE_NOISE_MULTIPLIER = 1.65
    }
}
