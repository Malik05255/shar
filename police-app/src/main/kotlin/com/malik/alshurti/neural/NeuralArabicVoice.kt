package com.malik.alshurti.neural

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

    // Synthesis and playback are deliberately separated. While the child hears chunk N,
    // Supertonic can already synthesize chunk N+1. This optimizes time-to-first-audio,
    // which matters more in a phone-call experience than full-response completion time.
    private val synthesisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-neural-synthesis").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    private val playbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-neural-playback").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
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
        synthesisExecutor.execute {
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

        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            callbacks.onSpeechFinished()
            return
        }

        val ticket = generation.incrementAndGet()
        stopTrack()
        val chunks = splitForConversation(normalized)
        val speechStarted = AtomicBoolean(false)

        synthesisExecutor.execute {
            try {
                var charOffset = 0
                chunks.forEachIndexed { index, chunk ->
                    if (ticket != generation.get()) return@execute

                    // Six diffusion steps is the conversational quality preset. The first
                    // chunk is intentionally short, so the user hears natural speech sooner
                    // without lowering voice quality to a robotic fast preset.
                    val result = engine.synthesize(chunk, 6, 1.04f)
                    if (ticket != generation.get() || result.audio.isEmpty()) return@forEachIndexed

                    val chunkStart = charOffset.toFloat() / normalized.length.toFloat()
                    charOffset += chunk.length
                    // Count skipped spaces/punctuation between our synthesized chunks.
                    while (charOffset < normalized.length && normalized[charOffset].isWhitespace()) {
                        charOffset++
                    }
                    val chunkEnd = if (index == chunks.lastIndex) {
                        1f
                    } else {
                        (charOffset.toFloat() / normalized.length.toFloat()).coerceIn(chunkStart, 1f)
                    }

                    val isFirst = index == 0
                    val isLast = index == chunks.lastIndex
                    playbackExecutor.execute {
                        playChunk(
                            ticket = ticket,
                            result = result,
                            overallStart = chunkStart,
                            overallEnd = chunkEnd,
                            isFirst = isFirst,
                            isLast = isLast,
                            speechStarted = speechStarted
                        )
                    }
                }
            } catch (t: Throwable) {
                if (ticket == generation.get()) {
                    if (speechStarted.get()) {
                        playbackExecutor.execute {
                            if (ticket == generation.get()) {
                                mainHandler.post(callbacks::onSpeechFinished)
                            }
                        }
                    } else {
                        mainHandler.post {
                            callbacks.onError(t.message ?: "تعذر توليد صوت الشرطي.")
                        }
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
        synthesisExecutor.shutdownNow()
        playbackExecutor.shutdownNow()
        runCatching { core?.close() }
        core = null
    }

    private fun playChunk(
        ticket: Long,
        result: SupertonicCore.Result,
        overallStart: Float,
        overallEnd: Float,
        isFirst: Boolean,
        isLast: Boolean,
        speechStarted: AtomicBoolean
    ) {
        if (ticket != generation.get()) return
        val durationMs = (result.audio.size * 1000L / result.sampleRate).coerceAtLeast(1L)
        val format = AudioFormat.Builder()
            .setSampleRate(result.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
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

        if (isFirst && speechStarted.compareAndSet(false, true)) {
            mainHandler.post { callbacks.onSpeechStarted(durationMs) }
        }
        track.play()

        val startedAt = System.nanoTime()
        while (ticket == generation.get()) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val localFraction = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val globalFraction = overallStart + (overallEnd - overallStart) * localFraction
            mainHandler.post { callbacks.onSpeechCursor(globalFraction.coerceIn(0f, 1f)) }
            if (elapsedMs >= durationMs) break
            try {
                Thread.sleep(58L)
            } catch (_: InterruptedException) {
                break
            }
        }

        val completed = ticket == generation.get()
        runCatching { track.stop() }
        runCatching { track.flush() }
        runCatching { track.release() }
        if (audioTrack === track) audioTrack = null

        if (completed && isLast) {
            mainHandler.post {
                callbacks.onSpeechCursor(1f)
                callbacks.onSpeechFinished()
            }
        }
    }

    private fun splitForConversation(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var remaining = text.trim()
        var target = FIRST_CHUNK_CHARS

        while (remaining.isNotEmpty()) {
            if (remaining.length <= target) {
                chunks += remaining
                break
            }

            val searchLimit = target.coerceAtMost(remaining.length - 1)
            val preferred = (searchLimit downTo (target / 2).coerceAtLeast(1)).firstOrNull { index ->
                remaining[index] in listOf('،', ',', '؛', ';', '؟', '?', '.', '!', ':')
            }
            val wordBoundary = preferred ?: (searchLimit downTo 1).firstOrNull { index ->
                remaining[index].isWhitespace()
            }
            val cut = (wordBoundary ?: searchLimit).coerceAtLeast(1)
            val chunk = remaining.substring(0, cut + 1).trim()
            if (chunk.isNotEmpty()) chunks += chunk
            remaining = remaining.substring((cut + 1).coerceAtMost(remaining.length)).trimStart()
            target = NEXT_CHUNK_CHARS
        }

        return chunks.ifEmpty { listOf(text) }
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

    private companion object {
        const val FIRST_CHUNK_CHARS = 48
        const val NEXT_CHUNK_CHARS = 88
    }
}
