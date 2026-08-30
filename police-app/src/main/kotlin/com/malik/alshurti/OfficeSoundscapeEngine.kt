package com.malik.alshurti

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Scene Foley engine.
 *
 * Priority is real recorded assets in assets/sfx/*.wav. When an optional asset is
 * missing the app synthesizes a short spatial cue instead of going silent. That
 * lets choreography remain deterministic while production-quality Foley can be
 * swapped in later without touching scene code.
 */
class OfficeSoundscapeEngine(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = mutableSetOf<Job>()

    @Volatile private var released = false

    fun play(cue: OfficeSoundCue) {
        if (released || cue == OfficeSoundCue.NONE) return
        val job = scope.launch {
            val assetName = assetFor(cue)
            val realAssetPlayed = assetName?.let { tryPlayAsset(it, cue) } ?: false
            if (!realAssetPlayed) playProcedural(cue)
        }
        synchronized(active) { active += job }
        job.invokeOnCompletion { synchronized(active) { active -= job } }
    }

    fun release() {
        released = true
        synchronized(active) {
            active.forEach { it.cancel() }
            active.clear()
        }
        scope.cancel()
    }

    private suspend fun tryPlayAsset(assetName: String, cue: OfficeSoundCue): Boolean {
        val descriptor = runCatching { appContext.assets.openFd("sfx/$assetName") }.getOrNull() ?: return false
        val temp = runCatching { File.createTempFile("office-sfx-", ".wav", appContext.cacheDir) }.getOrNull() ?: run {
            descriptor.close()
            return false
        }

        return try {
            descriptor.createInputStream().use { input -> temp.outputStream().use(input::copyTo) }
            descriptor.close()
            val completed = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(temp.absolutePath)
                setVolume(leftVolume(cue), rightVolume(cue))
                setOnCompletionListener {
                    runCatching { it.release() }
                    temp.delete()
                    completed.complete(true)
                }
                setOnErrorListener { mediaPlayer, _, _ ->
                    runCatching { mediaPlayer.release() }
                    temp.delete()
                    completed.complete(false)
                    true
                }
                prepare()
                start()
            }
            completed.await()
        } catch (_: Throwable) {
            temp.delete()
            false
        }
    }

    private suspend fun playProcedural(cue: OfficeSoundCue) {
        val samples = when (cue) {
            OfficeSoundCue.DOOR_HANDLE -> latchClick()
            OfficeSoundCue.DOOR_OPEN -> doorCreak(opening = true)
            OfficeSoundCue.DOOR_CLOSE -> doorClose()
            OfficeSoundCue.KNOCK -> knockPattern()
            OfficeSoundCue.PHONE_RING -> phoneRing()
            OfficeSoundCue.FOOTSTEPS_LEFT_TO_RIGHT -> footsteps(leftToRight = true)
            OfficeSoundCue.FOOTSTEPS_RIGHT_TO_LEFT -> footsteps(leftToRight = false)
            OfficeSoundCue.KEYBOARD -> keyboardBurst()
            OfficeSoundCue.PAPER -> paperRustle()
            OfficeSoundCue.CHAIR -> chairMove()
            OfficeSoundCue.RADIO_BEEP -> radioBeep()
            OfficeSoundCue.NONE -> return
        }
        playPcm(samples, cue)
    }

    private suspend fun playPcm(mono: FloatArray, cue: OfficeSoundCue) {
        if (mono.isEmpty() || released) return
        val stereo = ShortArray(mono.size * 2)
        val left = leftVolume(cue)
        val right = rightVolume(cue)
        mono.indices.forEach { index ->
            val sample = mono[index].coerceIn(-1f, 1f)
            stereo[index * 2] = (sample * left * Short.MAX_VALUE).toInt().toShort()
            stereo[index * 2 + 1] = (sample * right * Short.MAX_VALUE).toInt().toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(stereo.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(stereo, 0, stereo.size)
            track.play()
            delay((mono.size * 1000L / SAMPLE_RATE) + 80L)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun phoneRing(): FloatArray {
        val total = seconds(1.45)
        val out = FloatArray(total)
        for (i in out.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val pulse = when {
                t < 0.48f -> 1f
                t < 0.69f -> 0f
                t < 1.17f -> 1f
                else -> 0f
            }
            val tone = 0.50f * sin(2f * PI.toFloat() * 440f * t) +
                0.42f * sin(2f * PI.toFloat() * 480f * t)
            out[i] = tone * pulse * 0.36f
        }
        return out
    }

    private fun doorCreak(opening: Boolean): FloatArray {
        val out = FloatArray(seconds(0.78))
        val random = Random(if (opening) 1982 else 1983)
        for (i in out.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val progress = i.toFloat() / out.lastIndex.coerceAtLeast(1)
            val freq = if (opening) 115f + 95f * progress else 205f - 82f * progress
            val rough = (random.nextFloat() * 2f - 1f) * 0.16f
            val harmonic = sin(2f * PI.toFloat() * freq * t) * 0.34f +
                sin(2f * PI.toFloat() * freq * 2.05f * t) * 0.13f
            val envelope = sin(PI.toFloat() * progress).coerceAtLeast(0f)
            out[i] = (harmonic + rough) * envelope * 0.72f
        }
        return out
    }

    private fun doorClose(): FloatArray {
        val out = doorCreak(opening = false)
        addThump(out, out.size - seconds(0.16), amplitude = 0.9f, frequency = 68f)
        return out
    }

    private fun latchClick(): FloatArray {
        val out = FloatArray(seconds(0.18))
        addThump(out, seconds(0.015), 0.65f, 680f)
        addThump(out, seconds(0.075), 0.40f, 430f)
        return out
    }

    private fun knockPattern(): FloatArray {
        val out = FloatArray(seconds(0.72))
        listOf(0.05f, 0.27f, 0.47f).forEachIndexed { index, time ->
            addThump(out, seconds(time), if (index == 1) 0.72f else 0.84f, 115f)
        }
        return out
    }

    private fun footsteps(leftToRight: Boolean): FloatArray {
        val out = FloatArray(seconds(1.55))
        listOf(0.08f, 0.38f, 0.69f, 0.99f, 1.29f).forEachIndexed { index, time ->
            val amplitude = 0.48f - index * 0.025f
            addThump(out, seconds(time), amplitude, if (index % 2 == 0) 82f else 74f)
        }
        return out
    }

    private fun keyboardBurst(): FloatArray {
        val out = FloatArray(seconds(1.2))
        val random = Random(2407)
        var cursor = seconds(0.04)
        while (cursor < out.size - seconds(0.05)) {
            if (random.nextFloat() > 0.25f) addClick(out, cursor, 0.16f + random.nextFloat() * 0.11f)
            cursor += seconds(0.055f + random.nextFloat() * 0.07f)
        }
        return out
    }

    private fun paperRustle(): FloatArray {
        val out = FloatArray(seconds(0.9))
        val random = Random(9351)
        for (i in out.indices) {
            val progress = i.toFloat() / out.lastIndex.coerceAtLeast(1)
            val envelope = sin(PI.toFloat() * progress).coerceAtLeast(0f)
            out[i] = (random.nextFloat() * 2f - 1f) * envelope * 0.16f
        }
        return out
    }

    private fun chairMove(): FloatArray {
        val out = FloatArray(seconds(0.7))
        val random = Random(4421)
        for (i in out.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val progress = i.toFloat() / out.lastIndex.coerceAtLeast(1)
            val squeak = sin(2f * PI.toFloat() * (180f + 80f * sin(progress * PI.toFloat())) * t)
            val texture = (random.nextFloat() * 2f - 1f) * 0.08f
            out[i] = (squeak * 0.14f + texture) * sin(PI.toFloat() * progress).coerceAtLeast(0f)
        }
        return out
    }

    private fun radioBeep(): FloatArray {
        val out = FloatArray(seconds(0.28))
        for (i in out.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val envelope = if (t < 0.18f) 1f else ((0.28f - t) / 0.10f).coerceIn(0f, 1f)
            out[i] = sin(2f * PI.toFloat() * 1_050f * t) * envelope * 0.25f
        }
        return out
    }

    private fun addClick(buffer: FloatArray, start: Int, amplitude: Float) {
        val length = seconds(0.018)
        val random = Random(start)
        for (offset in 0 until length) {
            val index = start + offset
            if (index !in buffer.indices) break
            val envelope = 1f - offset.toFloat() / length.coerceAtLeast(1)
            buffer[index] += (random.nextFloat() * 2f - 1f) * envelope * amplitude
        }
    }

    private fun addThump(buffer: FloatArray, start: Int, amplitude: Float, frequency: Float) {
        val length = seconds(0.16)
        for (offset in 0 until length) {
            val index = start + offset
            if (index !in buffer.indices) break
            val t = offset.toFloat() / SAMPLE_RATE
            val envelope = exp(-t * 28f)
            buffer[index] += sin(2f * PI.toFloat() * frequency * t) * envelope * amplitude
        }
    }

    private fun seconds(value: Double): Int = (value * SAMPLE_RATE).toInt()
    private fun seconds(value: Float): Int = (value * SAMPLE_RATE).toInt()

    private fun leftVolume(cue: OfficeSoundCue): Float = when (cue) {
        OfficeSoundCue.DOOR_HANDLE, OfficeSoundCue.DOOR_OPEN, OfficeSoundCue.DOOR_CLOSE, OfficeSoundCue.KNOCK -> 0.82f
        OfficeSoundCue.PHONE_RING -> 0.28f
        OfficeSoundCue.FOOTSTEPS_LEFT_TO_RIGHT -> 0.68f
        OfficeSoundCue.FOOTSTEPS_RIGHT_TO_LEFT -> 0.38f
        OfficeSoundCue.KEYBOARD, OfficeSoundCue.PAPER, OfficeSoundCue.CHAIR -> 0.34f
        OfficeSoundCue.RADIO_BEEP -> 0.52f
        OfficeSoundCue.NONE -> 0f
    }

    private fun rightVolume(cue: OfficeSoundCue): Float = when (cue) {
        OfficeSoundCue.DOOR_HANDLE, OfficeSoundCue.DOOR_OPEN, OfficeSoundCue.DOOR_CLOSE, OfficeSoundCue.KNOCK -> 0.32f
        OfficeSoundCue.PHONE_RING -> 0.78f
        OfficeSoundCue.FOOTSTEPS_LEFT_TO_RIGHT -> 0.38f
        OfficeSoundCue.FOOTSTEPS_RIGHT_TO_LEFT -> 0.68f
        OfficeSoundCue.KEYBOARD, OfficeSoundCue.PAPER, OfficeSoundCue.CHAIR -> 0.30f
        OfficeSoundCue.RADIO_BEEP -> 0.44f
        OfficeSoundCue.NONE -> 0f
    }

    private fun assetFor(cue: OfficeSoundCue): String? = when (cue) {
        OfficeSoundCue.DOOR_HANDLE -> "door_handle.wav"
        OfficeSoundCue.DOOR_OPEN -> "door_open.wav"
        OfficeSoundCue.DOOR_CLOSE -> "door_close.wav"
        OfficeSoundCue.KNOCK -> "knock.wav"
        OfficeSoundCue.PHONE_RING -> "phone_ring.wav"
        OfficeSoundCue.FOOTSTEPS_LEFT_TO_RIGHT, OfficeSoundCue.FOOTSTEPS_RIGHT_TO_LEFT -> "footsteps.wav"
        OfficeSoundCue.KEYBOARD -> "keyboard.wav"
        OfficeSoundCue.PAPER -> "paper.wav"
        OfficeSoundCue.CHAIR -> "chair.wav"
        OfficeSoundCue.RADIO_BEEP -> "radio_beep.wav"
        OfficeSoundCue.NONE -> null
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
    }
}
