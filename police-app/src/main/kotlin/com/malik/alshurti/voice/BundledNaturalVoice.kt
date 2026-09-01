package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
        .setOnAudioFocusChangeListener { }
        .build()

    fun has(text: String): Boolean = findEntry(text) != null

    fun speak(text: String): Boolean {
        val entry = findEntry(text) ?: return false
        val resId = appContext.resources.getIdentifier(
            "voice_${entry.resourceName}",
            "raw",
            appContext.packageName
        )
        if (resId == 0) {
            callbacks.onError("ملف الصوت الطبيعي المحلي غير موجود: ${entry.resourceName}")
            return false
        }

        val ticket = generation.incrementAndGet()
        stopPlayerOnly()

        val localPlayer = MediaPlayer.create(appContext, resId) ?: run {
            callbacks.onError("تعذر فتح ملف الصوت الطبيعي المحلي.")
            return false
        }
        player = localPlayer
        localPlayer.setAudioAttributes(attributes)
        localPlayer.setVolume(1f, 1f)

        localPlayer.setOnErrorListener { _, what, extra ->
            if (ticket == generation.get()) callbacks.onError("فشل تشغيل الصوت الطبيعي المحلي: $what/$extra")
            stopPlayerOnly()
            true
        }
        localPlayer.setOnCompletionListener {
            if (ticket == generation.get()) {
                mainHandler.removeCallbacksAndMessages(ticket)
                callbacks.onSpeechCursor(1f)
                callbacks.onSpeechFinished()
            }
            stopPlayerOnly()
        }

        val focusResult = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        val durationMs = localPlayer.duration.toLong().coerceAtLeast(1L)
        localPlayer.start()
        callbacks.onSpeechStarted(durationMs)
        scheduleCursor(ticket, durationMs)
        return true
    }

    fun interrupt() {
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        stopPlayerOnly()
    }

    fun release() = interrupt()

    private fun scheduleCursor(ticket: Long, durationMs: Long) {
        val token = ticket
        val runnable = object : Runnable {
            override fun run() {
                val current = player
                if (ticket != generation.get() || current == null || !current.isPlaying) return
                val fraction = (current.currentPosition.toDouble() / durationMs.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
                callbacks.onSpeechCursor(fraction)
                mainHandler.postAtTime(this, token, android.os.SystemClock.uptimeMillis() + 40L)
            }
        }
        mainHandler.postAtTime(runnable, token, android.os.SystemClock.uptimeMillis() + 40L)
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
        .replace("  ", " ")
}
