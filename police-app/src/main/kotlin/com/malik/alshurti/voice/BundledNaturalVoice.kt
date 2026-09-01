package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays CI-generated Gemini Saudi voice assets bundled inside the APK.
 * This path requires no network at runtime and never falls back to platform TTS.
 */
class BundledNaturalVoice(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onSpeechStarted(durationMs: Long)
        fun onSpeechCursor(fraction: Float)
        fun onSpeechFinished()
        fun onError(message: String)
    }

    private data class Entry(val resourceName: String, val text: String)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val generation = AtomicLong(0L)
    private val catalog: List<Entry> = loadCatalog()

    @Volatile private var player: MediaPlayer? = null
    private var focusHeld = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> runCatching { player?.setVolume(1f, 1f) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching { player?.setVolume(0.35f, 0.35f) }
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Unit
            }
        }
        .build()

    fun has(text: String): Boolean {
        val entry = findEntry(text) ?: return false
        return resourceId(entry) != 0
    }

    fun speak(text: String): Boolean {
        val entry = findEntry(text) ?: return false
        val resId = resourceId(entry)
        if (resId == 0) {
            callbacks.onError("ملف الصوت الطبيعي المحلي غير موجود: ${entry.resourceName}")
            return false
        }

        val ticket = generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        stopPlayerOnly()

        val localPlayer = MediaPlayer()
        val descriptor = runCatching { appContext.resources.openRawResourceFd(resId) }.getOrNull()
        if (descriptor == null) {
            localPlayer.release()
            callbacks.onError("تعذر فتح ملف الصوت الطبيعي المحلي.")
            return false
        }

        try {
            // Set routing attributes BEFORE datasource/prepare. Some OEM stacks ignore attributes
            // applied after MediaPlayer.create() has already prepared the player.
            localPlayer.setAudioAttributes(attributes)
            localPlayer.setDataSource(
                descriptor.fileDescriptor,
                descriptor.startOffset,
                descriptor.length
            )
        } catch (error: Throwable) {
            descriptor.close()
            localPlayer.release()
            callbacks.onError("تعذر تجهيز ملف الصوت الطبيعي: ${error.message ?: "unknown"}")
            return false
        } finally {
            runCatching { descriptor.close() }
        }

        player = localPlayer
        localPlayer.setVolume(1f, 1f)
        localPlayer.setOnErrorListener { _, what, extra ->
            if (ticket == generation.get()) callbacks.onError("فشل تشغيل الصوت الطبيعي المحلي: $what/$extra")
            stopPlayerOnly()
            true
        }
        localPlayer.setOnCompletionListener {
            if (ticket == generation.get()) {
                callbacks.onSpeechCursor(1f)
                callbacks.onSpeechFinished()
            }
            stopPlayerOnly()
        }

        return try {
            localPlayer.prepare()
            if (ticket != generation.get() || player !== localPlayer) return false

            val focusResult = runCatching { audioManager.requestAudioFocus(focusRequest) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            focusHeld = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

            val durationMs = localPlayer.duration.toLong().coerceAtLeast(1L)
            localPlayer.start()
            callbacks.onSpeechStarted(durationMs)
            scheduleCursor(ticket, durationMs)
            schedulePlaybackWatchdog(ticket, localPlayer)
            true
        } catch (error: Throwable) {
            stopPlayerOnly()
            callbacks.onError("تعذر بدء الصوت الطبيعي المحلي: ${error.message ?: "unknown"}")
            false
        }
    }

    fun interrupt() {
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        stopPlayerOnly()
    }

    fun release() = interrupt()

    private fun scheduleCursor(ticket: Long, durationMs: Long) {
        val runnable = object : Runnable {
            override fun run() {
                val current = player
                if (ticket != generation.get() || current == null) return
                val playing = runCatching { current.isPlaying }.getOrDefault(false)
                if (!playing) return
                val position = runCatching { current.currentPosition }.getOrDefault(0)
                val fraction = (position.toDouble() / durationMs.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
                callbacks.onSpeechCursor(fraction)
                mainHandler.postDelayed(this, CURSOR_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(runnable, CURSOR_INTERVAL_MS)
    }

    private fun schedulePlaybackWatchdog(ticket: Long, expectedPlayer: MediaPlayer) {
        mainHandler.postDelayed({
            if (ticket != generation.get() || player !== expectedPlayer) return@postDelayed
            val isPlaying = runCatching { expectedPlayer.isPlaying }.getOrDefault(false)
            val position = runCatching { expectedPlayer.currentPosition }.getOrDefault(0)
            if (!isPlaying || position <= 0) {
                stopPlayerOnly()
                callbacks.onError("الصوت الطبيعي لم يبدأ فعلياً على مسار الوسائط في هذا الجهاز.")
            }
        }, PLAYBACK_WATCHDOG_MS)
    }

    private fun stopPlayerOnly() {
        val current = player
        player = null
        if (current != null) {
            runCatching { current.setOnCompletionListener(null) }
            runCatching { current.setOnErrorListener(null) }
            runCatching { current.stop() }
            runCatching { current.release() }
        }
        if (focusHeld) {
            focusHeld = false
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        }
    }

    private fun resourceId(entry: Entry): Int = appContext.resources.getIdentifier(
        "voice_${entry.resourceName}",
        "raw",
        appContext.packageName
    )

    private fun findEntry(text: String): Entry? {
        val normalized = normalize(text)
        return catalog.firstOrNull { normalize(it.text) == normalized }
    }

    private fun loadCatalog(): List<Entry> = runCatching {
        val json = appContext.assets.open("natural_voice_catalog.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(Entry(item.getString("id"), item.getString("text")))
            }
        }
    }.getOrElse { emptyList() }

    private fun normalize(value: String): String = value
        .trim()
        .replace('إ', 'ا')
        .replace('أ', 'ا')
        .replace('آ', 'ا')
        .replace(Regex("\\s+"), " ")

    private companion object {
        const val CURSOR_INTERVAL_MS = 40L
        const val PLAYBACK_WATCHDOG_MS = 420L
    }
}
