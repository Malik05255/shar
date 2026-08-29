package com.malik.alshurti.neural

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class NeuralArabicVoice(
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
    private val modelManager = SupertonicModelManager(appContext)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-neural-voice").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)

    @Volatile
    private var core: SupertonicCore? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var preparing = false

    fun isModelInstalled(): Boolean = modelManager.isInstalled()

    fun prepare(allowDownload: Boolean) {
        if (core != null || preparing) {
            if (core != null) mainHandler.post(callbacks::onReady)
            return
        }
        preparing = true
        val ticket = generation.incrementAndGet()
        executor.execute {
            try {
                val installed = modelManager.ensureInstalled(allowDownload) { percent, message ->
                    if (ticket == generation.get()) {
                        mainHandler.post { callbacks.onPreparing(percent, message) }
                    }
                }
                if (ticket != generation.get()) return@execute
                mainHandler.post { callbacks.onPreparing(100, "جاري تشغيل محرك الصوت العصبي…") }
                val loaded = SupertonicCore.load(installed.onnxDir, installed.voiceStyle)
                if (ticket != generation.get()) {
                    loaded.close()
                    return@execute
                }
                core?.close()
                core = loaded
                preparing = false
                mainHandler.post(callbacks::onReady)
            } catch (t: Throwable) {
                preparing = false
                mainHandler.post {
                    callbacks.onError(t.message ?: "تعذر تجهيز الصوت العصبي العربي.")
                }
            }
        }
    }

    fun speak(text: String) {
        val engine = core
        if (engine == null) {
            callbacks.onError("الصوت العصبي لم يجهز بعد.")
            return
        }
        val ticket = generation.incrementAndGet()
        stopTrack()
        executor.execute {
            try {
                // Six steps is the mobile conversational preset: far more natural than
                // platform TTS while keeping response latency lower than the reference 8.
                val result = engine.synthesize(text, 6, 1.04f)
                if (ticket != generation.get() || result.audio.isEmpty()) return@execute
                play(ticket, result)
            } catch (t: Throwable) {
                if (ticket == generation.get()) {
                    mainHandler.post {
                        callbacks.onError(t.message ?: "تعذر توليد صوت الشرطي.")
                    }
                }
            }
        }
    }

    fun interrupt() {
        generation.incrementAndGet()
        stopTrack()
    }

    fun release() {
        generation.incrementAndGet()
        stopTrack()
        executor.shutdownNow()
        runCatching { core?.close() }
        core = null
    }

    private fun play(ticket: Long, result: SupertonicCore.Result) {
        val durationMs = (result.audio.size * 1000L / result.sampleRate).coerceAtLeast(1L)
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder()
            .setSampleRate(result.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(channelMask)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(result.audio.size * Float.SIZE_BYTES)
            .build()
        audioTrack = track
        val written = track.write(result.audio, 0, result.audio.size, AudioTrack.WRITE_BLOCKING)
        if (written <= 0 || ticket != generation.get()) {
            runCatching { track.release() }
            if (audioTrack === track) audioTrack = null
            return
        }

        mainHandler.post { callbacks.onSpeechStarted(durationMs) }
        track.play()

        val startedAt = System.nanoTime()
        while (ticket == generation.get()) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val fraction = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            mainHandler.post { callbacks.onSpeechCursor(fraction) }
            if (elapsedMs >= durationMs) break
            try {
                Thread.sleep(70L)
            } catch (_: InterruptedException) {
                break
            }
        }

        val completed = ticket == generation.get()
        runCatching { track.stop() }
        runCatching { track.flush() }
        runCatching { track.release() }
        if (audioTrack === track) audioTrack = null
        if (completed) mainHandler.post(callbacks::onSpeechFinished)
    }

    @Synchronized
    private fun stopTrack() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}
