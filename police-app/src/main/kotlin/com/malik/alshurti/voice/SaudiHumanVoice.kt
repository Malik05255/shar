package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.malik.alshurti.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Production Saudi speech engine.
 *
 * There is deliberately no Android TTS or generic/local robotic fallback. The main
 * police persona and the background staff persona can use different ElevenLabs voice
 * ids while sharing the same proven Arabic model and playback path.
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
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            runnable,
            if (role == VoiceRole.POLICE) "alshorti-saudi-voice" else "alshorti-staff-voice"
        ).apply {
            priority = if (role == VoiceRole.POLICE) Thread.NORM_PRIORITY + 1 else Thread.NORM_PRIORITY
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var activeAudioFile: File? = null

    fun prepare() {
        val apiKey = BuildConfig.ELEVENLABS_API_KEY.trim()
        if (apiKey.isBlank()) {
            callbacks.onError(
                if (role == VoiceRole.POLICE) {
                    "الصوت السعودي غير مهيأ. أضف ELEVENLABS_API_KEY إلى بيئة البناء. لن أستخدم صوتاً روبوتياً كبديل."
                } else {
                    "صوت موظف المكتب غير مهيأ."
                }
            )
            return
        }

        val voiceId = configuredVoiceId()
        if (voiceId.isBlank()) {
            callbacks.onError(
                if (role == VoiceRole.POLICE) "لم يتم تحديد صوت سعودي للتطبيق."
                else "لم يتم تحديد صوت موظف المكتب."
            )
            return
        }

        callbacks.onPreparing(
            100,
            if (role == VoiceRole.POLICE) "جاري تجهيز الصوت السعودي الطبيعي…" else "جاري تجهيز صوت المكتب…"
        )
        callbacks.onReady()
    }

    fun speak(text: String) {
        val normalized = normalizeSaudiText(text)
        if (normalized.isBlank()) {
            callbacks.onSpeechFinished()
            return
        }

        val apiKey = BuildConfig.ELEVENLABS_API_KEY.trim()
        val voiceId = configuredVoiceId()
        if (apiKey.isBlank() || voiceId.isBlank()) {
            callbacks.onError(
                if (role == VoiceRole.POLICE) "الصوت السعودي غير مهيأ في نسخة التطبيق الحالية."
                else "صوت موظف المكتب غير مهيأ في نسخة التطبيق الحالية."
            )
            return
        }

        val ticket = generation.incrementAndGet()
        stopPlayback()
        callbacks.onPreparing(
            0,
            if (role == VoiceRole.POLICE) "جاري تجهيز رد الشرطي بصوت سعودي…" else "جاري تجهيز صوت الموظف…"
        )

        networkExecutor.execute {
            try {
                val audioFile = synthesizeToFile(
                    ticket = ticket,
                    apiKey = apiKey,
                    voiceId = voiceId,
                    text = normalized
                ) ?: return@execute

                if (ticket != generation.get()) {
                    audioFile.delete()
                    return@execute
                }

                mainHandler.post {
                    if (ticket == generation.get()) {
                        startPlayback(ticket, audioFile)
                    } else {
                        audioFile.delete()
                    }
                }
            } catch (t: Throwable) {
                if (ticket == generation.get()) {
                    mainHandler.post {
                        callbacks.onError(
                            t.message ?: if (role == VoiceRole.POLICE) {
                                "تعذر تشغيل الصوت السعودي الطبيعي."
                            } else {
                                "تعذر تشغيل صوت موظف المكتب."
                            }
                        )
                    }
                }
            }
        }
    }

    fun interrupt() {
        generation.incrementAndGet()
        mainHandler.post { stopPlayback() }
    }

    fun release() {
        generation.incrementAndGet()
        mainHandler.post { stopPlayback() }
        networkExecutor.shutdownNow()
    }

    private fun configuredVoiceId(): String = when (role) {
        VoiceRole.POLICE -> BuildConfig.ELEVENLABS_VOICE_ID.trim()
        VoiceRole.STAFF -> BuildConfig.ELEVENLABS_STAFF_VOICE_ID.trim().ifBlank {
            BuildConfig.ELEVENLABS_VOICE_ID.trim()
        }
    }

    private fun synthesizeToFile(
        ticket: Long,
        apiKey: String,
        voiceId: String,
        text: String
    ): File? {
        val endpoint = URL(
            "https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128"
        )
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
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
                        // Calm, confident and conversational. Avoid over-stability so the
                        // delivery does not flatten into a synthetic cadence.
                        put("stability", 0.42)
                        put("similarity_boost", 0.86)
                        put("style", 0.22)
                        put("speed", 1.0)
                    } else {
                        // The staff voice is intentionally a little quieter/faster and less
                        // styled so it reads as another person briefly entering the room.
                        put("stability", 0.54)
                        put("similarity_boost", 0.78)
                        put("style", 0.08)
                        put("speed", 1.06)
                    }
                    put("use_speaker_boost", true)
                }
            )
        }.toString()

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

            if (ticket != generation.get()) return null

            val prefix = if (role == VoiceRole.POLICE) "alshorti-saudi-" else "alshorti-staff-"
            val destination = File.createTempFile(prefix, ".mp3", appContext.cacheDir)
            connection.inputStream.use { input ->
                FileOutputStream(destination).buffered(128 * 1024).use { output ->
                    input.copyTo(output, 128 * 1024)
                }
            }

            if (destination.length() < 2_048L) {
                destination.delete()
                throw IllegalStateException("وصل ملف الصوت ناقصاً من الخدمة.")
            }
            return destination
        } finally {
            connection.disconnect()
        }
    }

    private fun startPlayback(ticket: Long, audioFile: File) {
        stopPlayback()
        activeAudioFile = audioFile

        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(audioFile.absolutePath)
            setVolume(if (role == VoiceRole.POLICE) 1f else 0.78f, if (role == VoiceRole.POLICE) 1f else 0.78f)
            setOnPreparedListener { prepared ->
                if (ticket != generation.get()) {
                    releasePlayer(prepared)
                    return@setOnPreparedListener
                }
                val duration = prepared.duration.coerceAtLeast(1)
                callbacks.onSpeechStarted(duration.toLong())
                prepared.start()
                scheduleCursor(ticket, prepared, duration)
            }
            setOnCompletionListener { completed ->
                if (ticket == generation.get()) {
                    callbacks.onSpeechCursor(1f)
                    callbacks.onSpeechFinished()
                }
                if (mediaPlayer === completed) mediaPlayer = null
                releasePlayer(completed)
                cleanupAudioFile(audioFile)
            }
            setOnErrorListener { failed, _, _ ->
                if (mediaPlayer === failed) mediaPlayer = null
                releasePlayer(failed)
                cleanupAudioFile(audioFile)
                if (ticket == generation.get()) {
                    callbacks.onError(
                        if (role == VoiceRole.POLICE) "تعذر تشغيل ملف الصوت السعودي."
                        else "تعذر تشغيل ملف صوت موظف المكتب."
                    )
                }
                true
            }
            prepareAsync()
        }
        mediaPlayer = player
    }

    private fun scheduleCursor(ticket: Long, player: MediaPlayer, durationMs: Int) {
        val tick = object : Runnable {
            override fun run() {
                if (ticket != generation.get() || mediaPlayer !== player) return
                val position = runCatching { player.currentPosition }.getOrDefault(0)
                val fraction = (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                callbacks.onSpeechCursor(fraction)
                if (runCatching { player.isPlaying }.getOrDefault(false)) {
                    mainHandler.postDelayed(this, 42L)
                }
            }
        }
        mainHandler.post(tick)
    }

    private fun stopPlayback() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) releasePlayer(player)
        activeAudioFile?.let(::cleanupAudioFile)
        activeAudioFile = null
    }

    private fun releasePlayer(player: MediaPlayer) {
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private fun cleanupAudioFile(file: File) {
        runCatching { file.delete() }
        if (activeAudioFile == file) activeAudioFile = null
    }

    private fun normalizeSaudiText(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace("…", "،")
        .trim()

    private companion object {
        const val ELEVENLABS_MODEL = "eleven_multilingual_v2"
    }
}
