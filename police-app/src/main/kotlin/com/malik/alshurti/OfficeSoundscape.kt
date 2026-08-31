package com.malik.alshurti

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Event-only office Foley.
 *
 * There is deliberately no continuous HVAC/room-tone oscillator. The previous implementation
 * generated permanent 55 Hz + 110 Hz sine components and looped them, which was audible as a
 * constant tone on phones and also competed with speech. Silence is now the baseline; only real
 * scene events produce short one-shot sounds.
 */
class OfficeSoundscape(context: Context) {
    @Suppress("UNUSED_PARAMETER")
    private val appContext = context.applicationContext

    private val effectsExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "alshorti-office-foley").apply {
            priority = Thread.NORM_PRIORITY - 1
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> Unit }
        }
    }

    @Volatile private var released = false

    /** Silence is intentional until a real office event occurs. */
    fun start() = Unit

    /** No persistent ambience exists anymore, so conversation phase has nothing to duck. */
    @Suppress("UNUSED_PARAMETER")
    fun setConversationPhase(phase: CallPhase) = Unit

    fun playCue(cue: OfficeCue) {
        if (released) return
        val samples = when (cue) {
            OfficeCue.PHONE_RING -> phoneRing()
            OfficeCue.DOOR_OPEN -> doorOpen()
            OfficeCue.DOOR_CLOSE -> doorClose()
            OfficeCue.STAFF_PASS,
            OfficeCue.FOOTSTEPS -> footsteps()
            OfficeCue.PAPER_RUSTLE -> paperRustle()
            OfficeCue.STAFF_SPEAK,
            OfficeCue.NONE -> null
        } ?: return
        playOneShot(samples)
    }

    fun release() {
        released = true
        runCatching { effectsExecutor.shutdownNow() }
    }

    private fun playOneShot(samples: ShortArray) {
        if (released) return
        try {
            effectsExecutor.execute {
                runCatching {
                    if (released) return@runCatching
                    val track = buildStaticTrack(samples)
                    try {
                        val written = track.write(samples, 0, samples.size)
                        if (written <= 0 || released) return@runCatching
                        track.setVolume(EFFECT_VOLUME)
                        track.play()
                        val durationMs = (samples.size * 1000L / SAMPLE_RATE).coerceAtLeast(1L)
                        try {
                            Thread.sleep(durationMs + 80L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    } finally {
                        safeRelease(track)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            Unit
        } catch (_: Throwable) {
            Unit
        }
    }

    private fun safeRelease(track: AudioTrack) {
        runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    private fun buildStaticTrack(samples: ShortArray): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).takeIf { it > 0 } ?: 0
        val requestedBytes = samples.size * Short.SIZE_BYTES
        val bufferBytes = maxOf(requestedBytes, minBuffer)

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    private fun phoneRing(): ShortArray {
        val seconds = 1.55
        val count = (SAMPLE_RATE * seconds).toInt()
        val out = ShortArray(count)
        repeat(count) { index ->
            val t = index.toDouble() / SAMPLE_RATE.toDouble()
            val cycle = t % 0.72
            val gate = if (cycle < 0.34) 1.0 else 0.0
            val attack = (cycle / 0.025).coerceIn(0.0, 1.0)
            val release = ((0.34 - cycle) / 0.05).coerceIn(0.0, 1.0)
            val env = gate * minOf(attack, release)
            val bell = sin(2.0 * PI * 440.0 * t) * 0.58 + sin(2.0 * PI * 620.0 * t) * 0.42
            out[index] = toPcm(bell * env * 0.30)
        }
        return out
    }

    private fun doorOpen(): ShortArray {
        val seconds = 1.05
        val count = (SAMPLE_RATE * seconds).toInt()
        val out = ShortArray(count)
        var filtered = 0.0
        repeat(count) { index ->
            val t = index.toDouble() / SAMPLE_RATE.toDouble()
            val progress = t / seconds
            val noise = Random.nextDouble(-1.0, 1.0)
            filtered = filtered * 0.88 + noise * 0.12
            val frequency = 170.0 - progress * 75.0
            val creak = sin(2.0 * PI * frequency * t + 4.0 * sin(2.0 * PI * 2.1 * t))
            val env = sin(PI * progress.coerceIn(0.0, 1.0)).coerceAtLeast(0.0)
            out[index] = toPcm((creak * 0.20 + filtered * 0.16) * env)
        }
        return out
    }

    private fun doorClose(): ShortArray {
        val seconds = 0.55
        val count = (SAMPLE_RATE * seconds).toInt()
        val out = ShortArray(count)
        repeat(count) { index ->
            val t = index.toDouble() / SAMPLE_RATE.toDouble()
            val thump = sin(2.0 * PI * 82.0 * t) * exp(-t * 13.0)
            val latchT = (t - 0.19).coerceAtLeast(0.0)
            val latch = if (t >= 0.19) sin(2.0 * PI * 760.0 * latchT) * exp(-latchT * 28.0) else 0.0
            out[index] = toPcm(thump * 0.55 + latch * 0.17)
        }
        return out
    }

    private fun footsteps(): ShortArray {
        val seconds = 1.25
        val count = (SAMPLE_RATE * seconds).toInt()
        val out = ShortArray(count)
        val steps = doubleArrayOf(0.08, 0.38, 0.70, 1.00)
        repeat(count) { index ->
            val t = index.toDouble() / SAMPLE_RATE.toDouble()
            var value = 0.0
            steps.forEachIndexed { i, start ->
                if (t >= start) {
                    val local = t - start
                    val freq = if (i % 2 == 0) 72.0 else 78.0
                    value += sin(2.0 * PI * freq * local) * exp(-local * 23.0) * 0.50
                }
            }
            out[index] = toPcm(value)
        }
        return out
    }

    private fun paperRustle(): ShortArray {
        val seconds = 0.75
        val count = (SAMPLE_RATE * seconds).toInt()
        val out = ShortArray(count)
        var high = 0.0
        repeat(count) { index ->
            val t = index.toDouble() / SAMPLE_RATE.toDouble()
            val noise = Random.nextDouble(-1.0, 1.0)
            high = noise - high * 0.32
            val envelope = sin(PI * (t / seconds)).coerceAtLeast(0.0)
            val flutter = 0.55 + 0.45 * sin(2.0 * PI * 7.0 * t)
            out[index] = toPcm(high * envelope * flutter * 0.16)
        }
        return out
    }

    private fun toPcm(value: Double): Short =
        (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val EFFECT_VOLUME = 0.34f
    }
}
