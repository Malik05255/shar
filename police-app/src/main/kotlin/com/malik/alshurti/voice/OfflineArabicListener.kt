package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Fully local, beep-free Arabic speech recognition.
 *
 * The same AudioRecord/VAD behavior used by the online listener is retained, but speech never
 * leaves the phone. Whisper Tiny multilingual runs through sherpa-onnx from app-private files.
 */
class OfflineArabicListener(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPreparing(percent: Int, message: String)
        fun onPrepared()
        fun onReady()
        fun onSpeechStarted()
        fun onFinalText(text: String)
        fun onError(message: String, recoverable: Boolean)
    }

    private val appContext = context.applicationContext
    private val modelManager = OfflineWhisperModelManager(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)

    private val prepareExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-whisper-prepare").apply { priority = Thread.NORM_PRIORITY }
    }
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-offline-capture").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-whisper-decode").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var released = false
    @Volatile private var listening = false
    @Volatile private var preparing = false

    fun isModelInstalled(): Boolean = modelManager.isInstalled()

    fun prepare(allowDownload: Boolean) {
        if (released) return
        if (recognizer != null) {
            dispatch(callbacks::onPrepared)
            return
        }
        if (preparing) return
        preparing = true
        val ticket = generation.incrementAndGet()
        try {
            prepareExecutor.execute {
                try {
                    val model = modelManager.ensureInstalled(allowDownload) { percent, message ->
                        if (ticket == generation.get() && !released) {
                            dispatch { callbacks.onPreparing(percent, message) }
                        }
                    }
                    if (ticket != generation.get() || released) return@execute
                    val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
                    val config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = INPUT_SAMPLE_RATE, featureDim = 80, dither = 0.0f),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = model.encoder.absolutePath,
                                decoder = model.decoder.absolutePath,
                                language = "ar",
                                task = "transcribe",
                                tailPaddings = 1000,
                                enableTokenTimestamps = false,
                                enableSegmentTimestamps = false
                            ),
                            numThreads = threads,
                            debug = false,
                            provider = "cpu",
                            tokens = model.tokens.absolutePath
                        ),
                        decodingMethod = "greedy_search"
                    )
                    val loaded = OfflineRecognizer(config = config)
                    if (ticket != generation.get() || released) {
                        loaded.release()
                        return@execute
                    }
                    recognizer?.release()
                    recognizer = loaded
                    preparing = false
                    dispatch(callbacks::onPrepared)
                } catch (t: Throwable) {
                    preparing = false
                    if (ticket == generation.get() && !released) {
                        dispatch { callbacks.onError(t.message ?: "تعذر تجهيز الاستماع المحلي.", false) }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            preparing = false
            dispatch { callbacks.onError("تعذر تجهيز الاستماع المحلي.", false) }
        }
    }

    fun start() {
        if (released || listening) return
        if (recognizer == null) {
            dispatch { callbacks.onError("الاستماع المحلي لم يجهز بعد.", true) }
            return
        }
        val ticket = generation.incrementAndGet()
        listening = true
        try {
            captureExecutor.execute { captureOneUtterance(ticket) }
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
        runCatching { recognizer?.release() }
        recognizer = null
        runCatching { prepareExecutor.shutdownNow() }
        runCatching { captureExecutor.shutdownNow() }
        runCatching { decodeExecutor.shutdownNow() }
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

            localRecorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(INPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, FRAME_SAMPLES * 2 * 8))
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
            dispatch(callbacks::onReady)

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
                val level = rms(current)
                if (!speechStarted) {
                    noiseFloor = (noiseFloor * 0.965 + level.coerceAtMost(NOISE_TRACKING_CEILING) * 0.035)
                        .coerceIn(MIN_NOISE_RMS, MAX_NOISE_RMS)
                    if (preRoll.size >= PRE_ROLL_FRAMES) preRoll.removeFirst()
                    preRoll.addLast(current)
                    val threshold = maxOf(ABSOLUTE_START_RMS, noiseFloor * START_NOISE_MULTIPLIER)
                    if (level >= threshold) hotFrames += 1 else hotFrames = (hotFrames - 1).coerceAtLeast(0)
                    if (hotFrames >= START_CONFIRM_FRAMES) {
                        speechStarted = true
                        utterance.addAll(preRoll)
                        preRoll.clear()
                        lastVoiceFrame = frameIndex
                        dispatch(callbacks::onSpeechStarted)
                    }
                } else {
                    utterance.add(current)
                    val threshold = maxOf(ABSOLUTE_CONTINUE_RMS, noiseFloor * CONTINUE_NOISE_MULTIPLIER)
                    if (level >= threshold) lastVoiceFrame = frameIndex
                    val silenceFrames = frameIndex - lastVoiceFrame
                    if ((utterance.size >= MIN_UTTERANCE_FRAMES && silenceFrames >= END_SILENCE_FRAMES) ||
                        utterance.size >= MAX_UTTERANCE_FRAMES
                    ) break
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
            val samples = toFloatSamples(utterance)
            try {
                decodeExecutor.execute { decode(ticket, samples) }
            } catch (_: RejectedExecutionException) {
                dispatch { callbacks.onError("تعذر معالجة الصوت محلياً.", true) }
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

    private fun decode(ticket: Long, samples: FloatArray) {
        if (ticket != generation.get() || released) return
        val active = recognizer
        if (active == null) {
            dispatch { callbacks.onError("الاستماع المحلي غير جاهز.", true) }
            return
        }
        try {
            val stream = active.createStream()
            try {
                stream.acceptWaveform(samples, INPUT_SAMPLE_RATE)
                active.decode(stream)
                if (ticket != generation.get() || released) return
                val text = active.getResult(stream).text.trim()
                if (text.isBlank()) dispatch { callbacks.onError("ما قدرت أفهم الكلام بوضوح.", true) }
                else dispatch { callbacks.onFinalText(text) }
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            if (ticket == generation.get() && !released) {
                dispatch { callbacks.onError(t.message ?: "تعذر التعرف على الكلام محلياً.", true) }
            }
        }
    }

    private fun toFloatSamples(frames: List<ShortArray>): FloatArray {
        val total = frames.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        frames.forEach { frame ->
            frame.forEach { sample -> out[offset++] = sample.toFloat() / 32768.0f }
        }
        return out
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

    private fun stopRecorder() {
        val active = recorder
        recorder = null
        if (active != null) stopRecorderInstance(active)
    }

    private fun stopRecorderInstance(active: AudioRecord) {
        runCatching { if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop() }
        runCatching { active.release() }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) runCatching(block)
        else mainHandler.post { runCatching(block) }
    }

    private companion object {
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
