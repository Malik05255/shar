package com.malik.alshurti

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.util.EnumMap

/** Event-only recorded Foley. No generated tones, hum, or synthetic ambience. */
class OfficeSoundscape(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val gate = OfficeFoleyGate()
    private val soundIds = EnumMap<OfficeCue, Int>(OfficeCue::class.java)
    private val loadedSoundIds = mutableSetOf<Int>()
    private val pendingCueAtMs = EnumMap<OfficeCue, Long>(OfficeCue::class.java)

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private var phase: CallPhase = CallPhase.STARTING
    private var started = false
    private var released = false
    private var startupCuePending = true

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(lock) {
                if (released || status != 0) return@synchronized
                loadedSoundIds += sampleId
                val cue = soundIds.entries.firstOrNull { it.value == sampleId }?.key
                    ?: return@synchronized
                val now = SystemClock.elapsedRealtime()
                val queuedAt = pendingCueAtMs.remove(cue)
                if (queuedAt != null && now - queuedAt <= MAX_PENDING_SYNC_MS) {
                    playLoadedLocked(cue, sampleId, now)
                }
                if (cue == OfficeCue.PAPER_RUSTLE && startupCuePending) {
                    startupCuePending = false
                    playLoadedLocked(cue, sampleId, now + OfficeFoleyPolicy.cooldownMs(cue))
                }
            }
        }
    }

    fun start() {
        synchronized(lock) {
            if (started || released) return
            started = true
            register(OfficeCue.PHONE_RING, R.raw.foley_phone_ring)
            register(OfficeCue.DOOR_OPEN, R.raw.foley_door_open)
            register(OfficeCue.DOOR_CLOSE, R.raw.foley_door_close)
            register(OfficeCue.PAPER_RUSTLE, R.raw.foley_page_turn)
        }
    }

    fun setConversationPhase(phase: CallPhase) {
        synchronized(lock) {
            if (!released) this.phase = phase
        }
    }

    fun playCue(cue: OfficeCue) {
        synchronized(lock) {
            if (!started || released || !OfficeFoleyPolicy.isSupported(cue)) return
            val sampleId = soundIds[cue] ?: return
            val now = SystemClock.elapsedRealtime()
            if (sampleId !in loadedSoundIds) {
                pendingCueAtMs[cue] = now
                return
            }
            playLoadedLocked(cue, sampleId, now)
        }
    }

    private fun register(cue: OfficeCue, rawResourceId: Int) {
        val sampleId = soundPool.load(appContext, rawResourceId, 1)
        if (sampleId != 0) soundIds[cue] = sampleId
    }

    private fun playLoadedLocked(cue: OfficeCue, sampleId: Int, nowMs: Long) {
        if (!gate.shouldPlay(cue, nowMs)) return
        val volume = (OfficeFoleyPolicy.baseVolume(cue) * OfficeFoleyPolicy.phaseGain(phase))
            .coerceIn(0f, 1f)
        if (volume <= 0f) return
        soundPool.play(sampleId, volume, volume, PLAY_PRIORITY, 0, 1.0f)
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            started = false
            pendingCueAtMs.clear()
            loadedSoundIds.clear()
            soundIds.clear()
            gate.reset()
            soundPool.release()
        }
    }

    private companion object {
        const val MAX_PENDING_SYNC_MS = 1_100L
        const val PLAY_PRIORITY = 1
    }
}
