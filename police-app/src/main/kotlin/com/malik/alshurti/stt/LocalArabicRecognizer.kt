package com.malik.alshurti.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * Chime-free Arabic speech recognition using raw AudioRecord + whisper.cpp.
 *
 * The free whisper-android AAR exposes file transcription rather than live streaming,
 * so this class performs lightweight endpointing locally: it records raw PCM, detects
 * speech/silence, writes a short WAV, then transcribes it with multilingual Whisper base.
 * No Android SpeechRecognizer UI/service is used, so OEM start/stop tones are avoided.
 */
class LocalArabicRecognizer(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPreparing(percent: Int, message: String)
        fun onReady()
        fun onListening()
        fun onSpeechStarted()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String, recoverable: Boolean)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val modelManager = ArabicWhisperModelManager(appContext)

    @Volatile private var whisperModel: WhisperModel? = null
    @Volatile private var preparing = false
    @Volatile private var allowDownload = true
    @Volatile private var pendingListen = false
    @Volatile private var listening = false

    private var captureJob: Job? = null
    @Volatile private var recorder: AudioRecord? = null

    fun prepare(allowDownload: Boolean) {
        this.allowDownload = allowDownload
        if (whisperModel != null) {
            main.post(callbacks::onReady)
            return
        }
        if (preparing) return
        preparing = true

        scope.launch {
            try {
                val modelFile = modelManager.ensureInstalled(allowDownload) { percent, message ->
                    main.post { callbacks.onPreparing(percent, message) }
                }
                main.post { callbacks.onPreparing(100, "جاري تحميل Whisper العربي…") }
                val loaded = Whisper.loadModel(appContext, modelFile.absolutePath)
                whisperModel?.let { runCatching { Whisper.releaseModel(it) } }
                whisperModel = loaded
                preparing = false
                main.post {
                    callbacks.onReady()
                    if (pendingListen) {
                        pendingListen = false
                        startListening()
                    }
                }
            } catch (t: Throwable) {
                preparing = false
                pendingListen = false
                main.post {
                    callbacks.onError(
                        t.message ?: "تعذر تجهيز Whisper العربي.",
                        false
                    )
                }
            }
        }
    }

    fun startListening() {
        if (listening) return
        if (whisperModel == null) {
            pendingListen = true
            prepare(allowDownload)
            return
        }

        stopCapture()
        listening = true
        main.post(callbacks::onListening)
        captureJob = scope.launch { captureOneUtterance() }
    }

    fun stop() {
        pendingListen = false
        listening = false
        stopCapture()
    }

    fun release() {
        pendingListen = false
        listening = false
        stopCapture()
        scope.cancel()
        whisperModel?.let { runCatching { Whisper.releaseModel(it) } }
        whisperModel = null
    }

    private suspend fun captureOneUtterance() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            listening = false
            main.post { callbacks.onError("تعذر تحديد حجم مخزن الميكروفون.", true) }
            return
        }

        val activeRecorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, FRAME_BYTES * 4)
            )
        } catch (t: Throwable) {
            listening = false
            main.post { callbacks.onError(t.message ?: "تعذر فتح الميكروفون.", true) }
            return
        }

        if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { activeRecorder.release() }
            listening = false
            main.post { callbacks.onError("تعذر تشغيل الميكروفون المحلي.", true) }
            return
        }

        recorder = activeRecorder
        val captured = ByteArrayOutputStream(MAX_PCM_BYTES)
        val frame = ByteArray(FRAME_BYTES)
        var speechStarted = false
        var loudFrames = 0
        var silentMs = 0
        var totalMs = 0

        try {
            activeRecorder.startRecording()
            while (isActive && listening) {
                val count = activeRecorder.read(frame, 0, frame.size)
                if (count <= 0) continue

                val level = rms16(frame, count)
                val loud = level >= SPEECH_RMS_THRESHOLD

                if (!speechStarted) {
                    if (loud) loudFrames++ else loudFrames = (loudFrames - 1).coerceAtLeast(0)
                    if (loudFrames >= START_FRAMES) {
                        speechStarted = true
                        main.post(callbacks::onSpeechStarted)
                    }
                }

                // Keep a small leading context even before endpointing decides speech began.
                captured.write(frame, 0, count)
                totalMs += FRAME_MS

                if (speechStarted) {
                    silentMs = if (loud) 0 else silentMs + FRAME_MS
                    if (silentMs >= END_SILENCE_MS && totalMs >= MIN_UTTERANCE_MS) break
                    if (totalMs >= MAX_UTTERANCE_MS) break
                } else if (totalMs >= WAIT_FOR_SPEECH_MS) {
                    break
                }
            }
        } catch (t: Throwable) {
            listening = false
            main.post { callbacks.onError(t.message ?: "حدث خطأ أثناء الاستماع.", true) }
            return
        } finally {
            runCatching { activeRecorder.stop() }
            runCatching { activeRecorder.release() }
            if (recorder === activeRecorder) recorder = null
        }

        if (!listening) return
        listening = false

        if (!speechStarted) {
            main.post { callbacks.onError("أنا سامعك، تكلم متى ما كنت جاهز.", true) }
            return
        }

        val pcm = trimLeadingSilence(captured.toByteArray())
        if (pcm.size < SAMPLE_RATE * 2 / 3) {
            main.post { callbacks.onError("ما سمعت جملة واضحة. جرّب مرة ثانية.", true) }
            return
        }

        val wav = File.createTempFile("alshorti-child-", ".wav", appContext.cacheDir)
        try {
            writeWav(wav, pcm)
            main.post { callbacks.onPreparing(100, "جاري فهم كلامك…") }
            val model = whisperModel ?: error("Whisper غير جاهز.")
            val result = Whisper.transcribe(
                model,
                wav.absolutePath,
                WhisperConfig(language = "ar")
            )
            val text = result.text
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isBlank()) {
                main.post { callbacks.onError("ما قدرت أفهم الكلام بوضوح. جرّب مرة ثانية.", true) }
            } else {
                main.post { callbacks.onFinal(text) }
            }
        } catch (t: Throwable) {
            main.post { callbacks.onError(t.message ?: "تعذر فهم الكلام العربي.", true) }
        } finally {
            wav.delete()
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        recorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        recorder = null
    }

    private fun rms16(bytes: ByteArray, count: Int): Double {
        var sum = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < count) {
            val lo = bytes[index].toInt() and 0xff
            val hi = bytes[index + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample.toDouble() * sample.toDouble()
            samples++
            index += 2
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    private fun trimLeadingSilence(pcm: ByteArray): ByteArray {
        val keepLeadBytes = SAMPLE_RATE * 2 * LEADING_CONTEXT_MS / 1000
        var firstLoudByte = 0
        var offset = 0
        while (offset + FRAME_BYTES <= pcm.size) {
            if (rms16(pcm, FRAME_BYTES.coerceAtMost(pcm.size - offset)) >= SPEECH_RMS_THRESHOLD) {
                firstLoudByte = (offset - keepLeadBytes).coerceAtLeast(0)
                break
            }
            offset += FRAME_BYTES
        }
        return pcm.copyOfRange(firstLoudByte, pcm.size)
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        FileOutputStream(file).use { out ->
            val dataSize = pcm.size
            val byteRate = SAMPLE_RATE * 2
            fun writeAscii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun writeIntLE(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
                out.write((value ushr 16) and 0xff)
                out.write((value ushr 24) and 0xff)
            }
            fun writeShortLE(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
            }

            writeAscii("RIFF")
            writeIntLE(36 + dataSize)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeIntLE(16)
            writeShortLE(1)
            writeShortLE(1)
            writeIntLE(SAMPLE_RATE)
            writeIntLE(byteRate)
            writeShortLE(2)
            writeShortLE(16)
            writeAscii("data")
            writeIntLE(dataSize)
            out.write(pcm)
            out.fd.sync()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 40
        const val FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000
        const val SPEECH_RMS_THRESHOLD = 620.0
        const val START_FRAMES = 2
        const val END_SILENCE_MS = 680
        const val LEADING_CONTEXT_MS = 260
        const val MIN_UTTERANCE_MS = 700
        const val WAIT_FOR_SPEECH_MS = 8_000
        const val MAX_UTTERANCE_MS = 12_000
        const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_UTTERANCE_MS / 1000
    }
}

private class ArabicWhisperModelManager(private val context: Context) {
    private val modelDir = File(context.filesDir, "speech-models").apply { mkdirs() }
    private val modelFile = File(modelDir, MODEL_FILE_NAME)

    fun ensureInstalled(
        allowDownload: Boolean,
        onProgress: (Int, String) -> Unit
    ): File {
        if (isValid(modelFile)) return modelFile
        if (!allowDownload) {
            error("Whisper العربي غير مثبت. شغّل الإنترنت مرة واحدة واضغط بدء المحادثة لتنزيله.")
        }

        val partial = File(modelDir, "$MODEL_FILE_NAME.part")
        partial.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AlShorti-Android")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("تعذر تنزيل Whisper العربي (${connection.responseCode}).")
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastPercent = -1
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0L) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent, "تنزيل Whisper العربي")
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (!isValid(partial)) {
            partial.delete()
            error("ملف Whisper العربي غير صالح أو التنزيل لم يكتمل.")
        }
        modelFile.delete()
        check(partial.renameTo(modelFile)) { "تعذر تثبيت Whisper العربي." }
        return modelFile
    }

    private fun isValid(file: File): Boolean = file.isFile && file.length() >= MIN_MODEL_BYTES

    private companion object {
        const val MODEL_FILE_NAME = "ggml-base.bin"
        const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin?download=true"
        const val MIN_MODEL_BYTES = 130_000_000L
        const val BUFFER_SIZE = 256 * 1024
    }
}
