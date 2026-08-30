package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.malik.alshurti.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Production Saudi speech engine.
 *
 * The path intentionally has no robotic Android-TTS fallback. Playback owns transient audio focus,
 * uses a short click-free gain ramp, keeps recurring lines in a bounded local cache, and protects
 * every MediaPlayer/network callback so an OEM audio-stack failure cannot terminate the app.
 */
class SaudiHumanVoice(
    context: Context,
    private val callbacks: Callbacks,
    private val role: VoiceRole = VoiceRole.POLICE
) {
    enum class VoiceRole { POLICE, STAFF }

    interface Callbacks {
        fun onPreparing(percent: Int, message: String)
        fun onReady()
        fun onSpeechStarted(durationMs: Long)
        fun onSpeechCursor(fraction: Float)
        fun onSpeechFinished()
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val voiceCacheDir = File(appContext.cacheDir, "saudi-human-voice-v3").apply { mkdirs() }

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusHeld = false
    private var pausedForFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            val player = mediaPlayer ?: return@post
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (pausedForFocus && !released) {
                        pausedForFocus = false
                        runCatching {
                            restoreTargetVolume(player)
                            player.start()
                        }
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (runCatching { player.isPlaying }.getOrDefault(false)) {
                        pausedForFocus = true
                        runCatching { player.pause() }
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS -> {
                    pausedForFocus = false
                    stopPlayback()
                }
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(speechAttributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
        .build()

    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            runnable,
            if (role == VoiceRole.POLICE) "alshorti-saudi-voice" else "alshorti-staff-voice"
        ).apply {
            priority = if (role == VoiceRole.POLICE) Thread.NORM_PRIORITY + 1 else Thread.NORM_PRIORITY
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                mainHandler.post { reportError(throwable.message) }
            }
        }
    }

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var activeAudioFile: File? = null

    @Volatile
    private var released = false

    fun prepare() {
        if (released) return
        pruneVoiceCache()

        val apiKey = BuildConfig.ELEVENLABS_API_KEY.trim()
        if (apiKey.isBlank()) {
            reportError(
                if (role == VoiceRole.POLICE) {
                    "الصوت السعودي غير مهيأ. أضف ELEVENLABS_API_KEY إلى بيئة البناء."
                } else {
                    "صوت موظف المكتب غير مهيأ."
                }
            )
            return
        }

        val voiceId = configuredVoiceId()
        if (voiceId.isBlank()) {
            reportError(
                if (role == VoiceRole.POLICE) "لم يتم تحديد صوت سعودي للتطبيق."
                else "لم يتم تحديد صوت موظف المكتب."
            )
            return
        }

        safeCallback {
            callbacks.onPreparing(100, "")
            callbacks.onReady()
        }
    }

    fun speak(text: String) {
        if (released) return
        val normalized = normalizeSaudiText(text)
        if (normalized.isBlank()) {
            safeCallback(callbacks::onSpeechFinished)
            return
        }

        val apiKey = BuildConfig.ELEVENLABS_API_KEY.trim()
        val voiceId = configuredVoiceId()
        if (apiKey.isBlank() || voiceId.isBlank()) {
            reportError(
                if (role == VoiceRole.POLICE) "الصوت السعودي غير مهيأ في نسخة التطبيق الحالية."
                else "صوت موظف المكتب غير مهيأ في نسخة التطبيق الحالية."
            )
            return
        }

        val ticket = generation.incrementAndGet()
        mainHandler.post { stopPlayback() }
        safeCallback { callbacks.onPreparing(0, "") }

        try {
            networkExecutor.execute {
                runCatching {
                    val audioFile = cachedOrSynthesize(
                        ticket = ticket,
                        apiKey = apiKey,
                        voiceId = voiceId,
                        text = normalized
                    ) ?: return@runCatching

                    if (ticket != generation.get() || released) return@runCatching

                    mainHandler.post {
                        if (ticket == generation.get() && !released) {
                            startPlaybackSafely(ticket, audioFile)
                        }
                    }
                }.onFailure { throwable ->
                    if (ticket == generation.get() && !released) {
                        mainHandler.post { reportError(throwable.message) }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!released) reportError(null)
        } catch (t: Throwable) {
            if (!released) reportError(t.message)
        }
    }

    fun interrupt() {
        generation.incrementAndGet()
        mainHandler.post { stopPlayback() }
    }

    fun release() {
        released = true
        generation.incrementAndGet()
        mainHandler.post { stopPlayback() }
        runCatching { networkExecutor.shutdownNow() }
    }

    private fun configuredVoiceId(): String = when (role) {
        VoiceRole.POLICE -> BuildConfig.ELEVENLABS_VOICE_ID.trim()
        VoiceRole.STAFF -> BuildConfig.ELEVENLABS_STAFF_VOICE_ID.trim().ifBlank {
            BuildConfig.ELEVENLABS_VOICE_ID.trim()
        }
    }

    private fun cachedOrSynthesize(
        ticket: Long,
        apiKey: String,
        voiceId: String,
        text: String
    ): File? {
        val destination = cacheFile(voiceId, text)
        if (destination.exists() && destination.length() >= MIN_AUDIO_BYTES) {
            destination.setLastModified(System.currentTimeMillis())
            safeCallback { callbacks.onPreparing(100, "") }
            return destination
        }

        val endpoint = URL(
            "https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128"
        )
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
            setRequestProperty("User-Agent", "AlShorti-Android/${BuildConfig.VERSION_NAME}")
        }

        val payload = JSONObject().apply {
            put("text", text)
            put("model_id", ELEVENLABS_MODEL)
            put("language_code", "ar")
            put(
                "voice_settings",
                JSONObject().apply {
                    if (role == VoiceRole.POLICE) {
                        // Slightly slower, highly similar and less stylized gives cleaner Saudi
                        // consonants and avoids the exaggerated/robotic cadence heard in testing.
                        put("stability", 0.38)
                        put("similarity_boost", 0.91)
                        put("style", 0.12)
                        put("speed", 0.96)
                    } else {
                        put("stability", 0.50)
                        put("similarity_boost", 0.84)
                        put("style", 0.06)
                        put("speed", 1.00)
                    }
                    put("use_speaker_boost", true)
                }
            )
        }.toString()

        val partial = File(destination.parentFile, "${destination.name}.part")
        runCatching { partial.delete() }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                val details = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                val reason = when (status) {
                    401 -> "مفتاح خدمة الصوت غير صالح."
                    402 -> "رصيد خدمة الصوت السعودي غير كافٍ."
                    422 -> "الصوت السعودي المختار غير متاح لهذا الطلب."
                    429 -> "خدمة الصوت مشغولة حالياً؛ حاول بعد قليل."
                    else -> "خدمة الصوت أعادت خطأ $status."
                }
                throw IllegalStateException(
                    if (details.isBlank()) reason else "$reason (${details.take(180)})"
                )
            }

            if (ticket != generation.get() || released) return null

            connection.inputStream.use { input ->
                FileOutputStream(partial).buffered(128 * 1024).use { output ->
                    input.copyTo(output, 128 * 1024)
                    output.flush()
                }
            }

            if (partial.length() < MIN_AUDIO_BYTES) {
                partial.delete()
                throw IllegalStateException("وصل ملف الصوت ناقصاً من الخدمة.")
            }

            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            destination.setLastModified(System.currentTimeMillis())
            pruneVoiceCache()
            return destination
        } finally {
            connection.disconnect()
            runCatching { partial.delete() }
        }
    }

    private fun startPlaybackSafely(ticket: Long, audioFile: File) {
        runCatching {
            startPlayback(ticket, audioFile)
        }.onFailure { throwable ->
            if (ticket == generation.get() && !released) reportError(throwable.message)
        }
    }

    private fun startPlayback(ticket: Long, audioFile: File) {
        stopPlayback()
        if (released) return
        activeAudioFile = audioFile

        val player = MediaPlayer()
        try {
            player.setAudioAttributes(speechAttributes)
            player.setDataSource(audioFile.absolutePath)
            player.setVolume(0f, 0f)

            player.setOnPreparedListener { prepared ->
                runCatching {
                    if (ticket != generation.get() || released) {
                        releasePlayer(prepared)
                        return@runCatching
                    }
                    if (!requestAudioFocus()) {
                        throw IllegalStateException("تعذر الحصول على أولوية الصوت.")
                    }

                    val duration = prepared.duration.coerceAtLeast(1)
                    safeCallback { callbacks.onSpeechStarted(duration.toLong()) }
                    prepared.start()
                    rampToTargetVolume(ticket, prepared)
                    scheduleCursor(ticket, prepared, duration)
                }.onFailure { throwable ->
                    if (mediaPlayer === prepared) mediaPlayer = null
                    releasePlayer(prepared)
                    activeAudioFile = null
                    abandonAudioFocus()
                    if (ticket == generation.get() && !released) reportError(throwable.message)
                }
            }

            player.setOnCompletionListener { completed ->
                runCatching {
                    if (ticket == generation.get() && !released) {
                        safeCallback {
                            callbacks.onSpeechCursor(1f)
                            callbacks.onSpeechFinished()
                        }
                    }
                }
                if (mediaPlayer === completed) mediaPlayer = null
                activeAudioFile = null
                releasePlayer(completed)
                abandonAudioFocus()
            }

            player.setOnErrorListener { failed, _, _ ->
                if (mediaPlayer === failed) mediaPlayer = null
                activeAudioFile = null
                releasePlayer(failed)
                abandonAudioFocus()
                if (ticket == generation.get() && !released) {
                    reportError(
                        if (role == VoiceRole.POLICE) "تعذر تشغيل ملف الصوت السعودي."
                        else "تعذر تشغيل ملف صوت موظف المكتب."
                    )
                }
                true
            }

            mediaPlayer = player
            player.prepareAsync()
        } catch (t: Throwable) {
            if (mediaPlayer === player) mediaPlayer = null
            activeAudioFile = null
            releasePlayer(player)
            abandonAudioFocus()
            throw t
        }
    }

    private fun rampToTargetVolume(ticket: Long, player: MediaPlayer) {
        val target = targetVolume()
        repeat(VOLUME_RAMP_STEPS) { index ->
            mainHandler.postDelayed({
                if (ticket != generation.get() || mediaPlayer !== player || released) return@postDelayed
                val gain = target * ((index + 1f) / VOLUME_RAMP_STEPS.toFloat())
                runCatching { player.setVolume(gain, gain) }
            }, index * VOLUME_RAMP_STEP_MS)
        }
    }

    private fun restoreTargetVolume(player: MediaPlayer) {
        val gain = targetVolume()
        runCatching { player.setVolume(gain, gain) }
    }

    private fun targetVolume(): Float = if (role == VoiceRole.POLICE) 1f else 0.72f

    private fun requestAudioFocus(): Boolean {
        val result = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonAudioFocus() {
        if (!focusHeld) return
        focusHeld = false
        pausedForFocus = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private fun scheduleCursor(ticket: Long, player: MediaPlayer, durationMs: Int) {
        val tick = object : Runnable {
            override fun run() {
                if (ticket != generation.get() || mediaPlayer !== player || released) return
                runCatching {
                    val position = player.currentPosition
                    val fraction = (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    safeCallback { callbacks.onSpeechCursor(fraction) }
                    if (player.isPlaying || pausedForFocus) mainHandler.postDelayed(this, 36L)
                }.onFailure { throwable ->
                    if (mediaPlayer === player) mediaPlayer = null
                    activeAudioFile = null
                    releasePlayer(player)
                    abandonAudioFocus()
                    if (ticket == generation.get() && !released) reportError(throwable.message)
                }
            }
        }
        mainHandler.post(tick)
    }

    private fun stopPlayback() {
        val player = mediaPlayer
        mediaPlayer = null
        activeAudioFile = null
        if (player != null) releasePlayer(player)
        abandonAudioFocus()
    }

    private fun releasePlayer(player: MediaPlayer) {
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private fun cacheFile(voiceId: String, text: String): File {
        val payload = "$CACHE_SCHEMA|${role.name}|$voiceId|$ELEVENLABS_MODEL|$text"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(voiceCacheDir, "$digest.mp3")
    }

    private fun pruneVoiceCache() {
        runCatching {
            val files = voiceCacheDir.listFiles { file -> file.isFile && file.extension == "mp3" }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            var totalBytes = 0L
            files.forEachIndexed { index, file ->
                totalBytes += file.length()
                if (index >= MAX_CACHE_FILES || totalBytes > MAX_CACHE_BYTES) {
                    runCatching { file.delete() }
                }
            }
            voiceCacheDir.listFiles { file -> file.name.endsWith(".part") }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun reportError(message: String?) {
        if (released) return
        safeCallback {
            callbacks.onError(
                message ?: if (role == VoiceRole.POLICE) {
                    "تعذر تشغيل الصوت السعودي الطبيعي."
                } else {
                    "تعذر تشغيل صوت موظف المكتب."
                }
            )
        }
    }

    private inline fun safeCallback(block: () -> Unit) {
        runCatching(block)
    }

    private fun normalizeSaudiText(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace("…", "،")
        .replace("..", ".")
        .trim()

    private companion object {
        const val ELEVENLABS_MODEL = "eleven_multilingual_v2"
        const val CACHE_SCHEMA = "v3"
        const val MIN_AUDIO_BYTES = 2_048L
        const val MAX_CACHE_FILES = 32
        const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
        const val VOLUME_RAMP_STEPS = 6
        const val VOLUME_RAMP_STEP_MS = 14L
    }
}
