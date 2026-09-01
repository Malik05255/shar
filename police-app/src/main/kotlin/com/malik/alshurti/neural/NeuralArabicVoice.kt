package com.malik.alshurti.neural

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class NeuralArabicVoice(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPreparing(percent: Int, message: String)
        fun onReady()
        fun onSpeechStarted(durationMs: Long)
        fun onSpeechFrame(fraction: Float, energy: Float)
        fun onSpeechFinished()
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val modelManager = SupertonicModelManager(appContext)
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
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(speechAttributes)
        .setWillPauseWhenDucked(false)
        .build()

    @Volatile private var core: SupertonicCore? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var preparing = false
    @Volatile private var focusHeld = false

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
        stopPlayback()
        val chunks = splitForConversation(normalized)
        val speechStarted = AtomicBoolean(false)

        synthesisExecutor.execute {
            try {
                var charOffset = 0
                chunks.forEachIndexed { index, chunk ->
                    if (ticket != generation.get()) return@execute
                    val result = engine.synthesize(chunk, 6, 1.04f)
                    if (ticket != generation.get() || result.audio.isEmpty()) return@forEachIndexed

                    val chunkStart = charOffset.toFloat() / normalized.length.toFloat()
                    charOffset += chunk.length
                    while (charOffset < normalized.length && normalized[charOffset].isWhitespace()) {
                        charOffset++
                    }
                    val chunkEnd = if (index == chunks.lastIndex) {
                        1f
                    } else {
                        (charOffset.toFloat() / normalized.length.toFloat()).coerceIn(chunkStart, 1f)
                    }

                    playbackExecutor.execute {
                        runCatching {
                            playChunk(
                                ticket = ticket,
                                result = result,
                                overallStart = chunkStart,
                                overallEnd = chunkEnd,
                                isFirst = index == 0,
                                isLast = index == chunks.lastIndex,
                                speechStarted = speechStarted
                            )
                        }.onFailure { error ->
                            if (ticket == generation.get()) {
                                mainHandler.post {
                                    callbacks.onError(error.message ?: "تعذر تشغيل صوت الشرطي على الجهاز.")
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (ticket == generation.get()) {
                    if (speechStarted.get()) {
                        playbackExecutor.execute {
                            if (ticket == generation.get()) mainHandler.post(callbacks::onSpeechFinished)
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
        stopPlayback()
    }

    fun release() {
        generation.incrementAndGet()
        stopPlayback()
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
        runCatching {
            playChunkWithAudioTrack(ticket, result, overallStart, overallEnd, isFirst, isLast, speechStarted)
        }.recoverCatching {
            if (ticket != generation.get()) return
            playChunkWithMediaPlayer(ticket, result, overallStart, overallEnd, isFirst, isLast, speechStarted)
        }.getOrThrow()
    }

    private fun playChunkWithAudioTrack(
        ticket: Long,
        result: SupertonicCore.Result,
        overallStart: Float,
        overallEnd: Float,
        isFirst: Boolean,
        isLast: Boolean,
        speechStarted: AtomicBoolean
    ) {
        val durationMs = (result.audio.size * 1000L / result.sampleRate).coerceAtLeast(1L)
        val format = AudioFormat.Builder()
            .setSampleRate(result.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(speechAttributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(result.audio.size * Float.SIZE_BYTES)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("تعذر تهيئة مسار الصوت المحلي.")
        }
        audioTrack = track

        try {
            val written = track.write(result.audio, 0, result.audio.size, AudioTrack.WRITE_BLOCKING)
            if (written <= 0 || ticket != generation.get()) return
            requestAudioFocus()
            track.setVolume(1f)
            track.play()

            if (durationMs > AUDIO_START_WATCHDOG_MS * 2) {
                Thread.sleep(AUDIO_START_WATCHDOG_MS)
                if (ticket == generation.get() && track.playbackHeadPosition == 0) {
                    throw IllegalStateException("AudioTrack لم يبدأ فعليًا على هذا الجهاز.")
                }
            }

            if (isFirst && speechStarted.compareAndSet(false, true)) {
                mainHandler.post { callbacks.onSpeechStarted(durationMs) }
            }
            publishLipFrames(ticket, result, overallStart, overallEnd, durationMs) {
                track.playbackHeadPosition.toLong().coerceAtLeast(0L) / result.audio.size.toDouble()
            }
            if (ticket == generation.get() && isLast) finishSpeech()
        } finally {
            if (audioTrack === track) audioTrack = null
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
            abandonAudioFocus()
        }
    }

    private fun playChunkWithMediaPlayer(
        ticket: Long,
        result: SupertonicCore.Result,
        overallStart: Float,
        overallEnd: Float,
        isFirst: Boolean,
        isLast: Boolean,
        speechStarted: AtomicBoolean
    ) {
        val durationMs = (result.audio.size * 1000L / result.sampleRate).coerceAtLeast(1L)
        val wavFile = File(appContext.cacheDir, "alshorti-supertonic-$ticket-${System.nanoTime()}.wav")
        wavFile.writeBytes(floatPcmToWav(result.audio, result.sampleRate))
        val player = MediaPlayer()
        mediaPlayer = player

        try {
            player.setAudioAttributes(speechAttributes)
            player.setDataSource(wavFile.absolutePath)
            player.prepare()
            if (ticket != generation.get()) return
            requestAudioFocus()
            player.setVolume(1f, 1f)
            player.start()
            Thread.sleep(MEDIA_START_WATCHDOG_MS)
            if (ticket == generation.get() && !player.isPlaying && player.currentPosition <= 0) {
                throw IllegalStateException("MediaPlayer لم يبدأ تشغيل الصوت المحلي.")
            }

            if (isFirst && speechStarted.compareAndSet(false, true)) {
                mainHandler.post { callbacks.onSpeechStarted(durationMs) }
            }
            publishLipFrames(ticket, result, overallStart, overallEnd, durationMs) {
                player.currentPosition.toDouble() / durationMs.toDouble()
            }
            if (ticket == generation.get() && isLast) finishSpeech()
        } finally {
            if (mediaPlayer === player) mediaPlayer = null
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
            runCatching { wavFile.delete() }
            abandonAudioFocus()
        }
    }

    private fun publishLipFrames(
        ticket: Long,
        result: SupertonicCore.Result,
        overallStart: Float,
        overallEnd: Float,
        durationMs: Long,
        playbackFraction: () -> Double
    ) {
        val energyCalibration = PcmSpeechEnergy.calibrate(result.audio, result.sampleRate)
        val startedAt = System.nanoTime()
        var smoothedEnergy = 0f
        while (ticket == generation.get()) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val localFraction = playbackFraction().toFloat().coerceIn(0f, 1f)
            val fallbackFraction = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val effectiveFraction = maxOf(localFraction, fallbackFraction)
            val globalFraction = overallStart + (overallEnd - overallStart) * effectiveFraction
            val rawEnergy = PcmSpeechEnergy.normalizedAt(
                result.audio,
                result.sampleRate,
                effectiveFraction,
                energyCalibration
            )
            smoothedEnergy = PcmSpeechEnergy.smooth(smoothedEnergy, rawEnergy)
            val callbackFraction = globalFraction.coerceIn(0f, 1f)
            val callbackEnergy = smoothedEnergy
            mainHandler.post { callbacks.onSpeechFrame(callbackFraction, callbackEnergy) }
            if (effectiveFraction >= 0.999f || elapsedMs >= durationMs + PLAYBACK_GRACE_MS) break
            try {
                Thread.sleep(LIP_FRAME_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun finishSpeech() {
        mainHandler.post {
            callbacks.onSpeechFrame(1f, 0f)
            callbacks.onSpeechFinished()
        }
    }

    private fun requestAudioFocus() {
        focusHeld = runCatching {
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
    }

    private fun abandonAudioFocus() {
        if (!focusHeld) return
        focusHeld = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private fun floatPcmToWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
            pcm[index * 2] = (value and 0xff).toByte()
            pcm[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
        val out = ByteArrayOutputStream(pcm.size + 44)
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
        le32(pcm.size + 36)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1)
        le16(1)
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        ascii("data")
        le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
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
    private fun stopPlayback() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        abandonAudioFocus()
    }

    private companion object {
        const val FIRST_CHUNK_CHARS = 48
        const val NEXT_CHUNK_CHARS = 88
        const val AUDIO_START_WATCHDOG_MS = 120L
        const val MEDIA_START_WATCHDOG_MS = 90L
        const val LIP_FRAME_MS = 58L
        const val PLAYBACK_GRACE_MS = 700L
    }
}
