package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Conversational Saudi Arabic voice for ONLINE mode.
 *
 * This talks to the public NAMAA Saudi Voice Gradio Space, whose model is based on
 * NAMAA-Saudi-TTS / Chatterbox Multilingual. No API key is embedded in the app.
 *
 * Important product rule: this class is never used as an "unlimited cloud" promise.
 * Public Spaces can queue, sleep, or become unavailable. The engine is isolated so a
 * self-hosted NAMAA endpoint can replace [BASE_URL] without changing the call UI.
 */
class NamaaSaudiVoice(
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
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-namaa-voice").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var released = false

    fun prepare() {
        if (released) return
        callbacks.onPreparing(100, "الصوت السعودي جاهز")
        callbacks.onReady()
    }

    fun speak(text: String) {
        val clean = normalizeForSpeech(text)
        if (clean.isBlank()) {
            callbacks.onSpeechFinished()
            return
        }

        val ticket = generation.incrementAndGet()
        stopPlayer()
        io.execute {
            try {
                val audio = synthesize(ticket, clean)
                if (ticket != generation.get() || released) {
                    audio.delete()
                    return@execute
                }
                main.post { play(ticket, audio) }
            } catch (t: Throwable) {
                if (ticket == generation.get() && !released) {
                    main.post {
                        callbacks.onError(t.message ?: "تعذر تشغيل الصوت السعودي الآن.")
                    }
                }
            }
        }
    }

    fun interrupt() {
        generation.incrementAndGet()
        main.post { stopPlayer() }
    }

    fun release() {
        released = true
        generation.incrementAndGet()
        main.post { stopPlayer() }
        io.shutdownNow()
    }

    private fun synthesize(ticket: Long, text: String): File {
        if (ticket != generation.get()) error("cancelled")
        val eventId = createJob(text)
        val output = waitForAudio(eventId, ticket)
        if (ticket != generation.get()) error("cancelled")
        return downloadAudio(output, ticket)
    }

    private fun createJob(text: String): String {
        val payload = JSONObject().apply {
            put(
                "data",
                JSONArray().apply {
                    put(text.take(MAX_TEXT_CHARS))
                    put(JSONObject.NULL) // Space falls back to its built-in Saudi reference voice.
                    put(EXAGGERATION)
                    put(TEMPERATURE)
                    put(0)
                    put(CFG_WEIGHT)
                }
            )
        }

        val connection = openConnection("$BASE_URL/gradio_api/call/$API_NAME", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            ensureSuccess(connection, "تعذر بدء توليد الصوت السعودي")
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(response).optString("event_id").takeIf { it.isNotBlank() }
                ?: error("لم ترجع خدمة الصوت رقم الطلب.")
        } finally {
            connection.disconnect()
        }
    }

    private fun waitForAudio(eventId: String, ticket: Long): AudioLocation {
        val connection = openConnection("$BASE_URL/gradio_api/call/$API_NAME/$eventId", "GET").apply {
            setRequestProperty("Accept", "text/event-stream")
            readTimeout = EVENT_READ_TIMEOUT_MS
        }

        try {
            ensureSuccess(connection, "تعذر انتظار الصوت السعودي")
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                var event = ""
                while (ticket == generation.get()) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substringAfter(':').trim()
                        line.startsWith("data:") -> {
                            val data = line.substringAfter(':').trim()
                            if (event == "error") error(parseServerError(data))
                            if (event == "complete") {
                                findAudioLocation(data)?.let { return it }
                                error("خدمة الصوت انتهت بدون ملف صوتي.")
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        error("انقطع الاتصال بخدمة الصوت قبل اكتمال الرد.")
    }

    private fun findAudioLocation(data: String): AudioLocation? {
        val root: Any = runCatching { JSONArray(data) }
            .getOrElse { runCatching { JSONObject(data) }.getOrNull() ?: return null }
        return findAudioRecursive(root)
    }

    private fun findAudioRecursive(value: Any?): AudioLocation? = when (value) {
        is JSONObject -> {
            value.optString("url").takeIf { it.isNotBlank() }?.let {
                return AudioLocation(url = it, path = null)
            }
            value.optString("path").takeIf { it.isNotBlank() }?.let {
                return AudioLocation(url = null, path = it)
            }
            value.keys().asSequence()
                .mapNotNull { key -> findAudioRecursive(value.opt(key)) }
                .firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { index -> findAudioRecursive(value.opt(index)) }
            .firstOrNull()
        is String -> when {
            value.startsWith("http://") || value.startsWith("https://") -> AudioLocation(value, null)
            value.contains("/gradio_api/file=") -> AudioLocation(value, null)
            value.endsWith(".wav") || value.endsWith(".flac") || value.endsWith(".mp3") ->
                AudioLocation(null, value)
            else -> null
        }
        else -> null
    }

    private fun downloadAudio(location: AudioLocation, ticket: Long): File {
        val resolvedUrl = when {
            !location.url.isNullOrBlank() -> resolveReturnedUrl(location.url)
            !location.path.isNullOrBlank() -> gradioFileUrl(location.path)
            else -> error("لم ترجع خدمة الصوت موقع الملف.")
        }

        val target = File.createTempFile("saudi-voice-", ".wav", appContext.cacheDir)
        val connection = openConnection(resolvedUrl, "GET")
        try {
            ensureSuccess(connection, "تعذر تنزيل الرد الصوتي")
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (ticket == generation.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (ticket != generation.get()) {
            target.delete()
            error("cancelled")
        }
        if (target.length() < 1_024L) {
            target.delete()
            error("ملف الصوت السعودي غير صالح.")
        }
        return target
    }

    private fun resolveReturnedUrl(value: String): String = when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("/gradio_api/") -> "$BASE_URL$value"
        value.startsWith("gradio_api/") -> "$BASE_URL/$value"
        else -> gradioFileUrl(value)
    }

    private fun gradioFileUrl(path: String): String {
        val encoded = URLEncoder.encode(path, Charsets.UTF_8.name()).replace("+", "%20")
        return "$BASE_URL/gradio_api/file=$encoded"
    }

    private fun play(ticket: Long, file: File) {
        if (ticket != generation.get() || released) {
            file.delete()
            return
        }
        stopPlayer()

        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnPreparedListener { prepared ->
                if (ticket != generation.get() || released) {
                    prepared.release()
                    file.delete()
                    return@setOnPreparedListener
                }
                val duration = prepared.duration.coerceAtLeast(1)
                callbacks.onSpeechStarted(duration.toLong())
                prepared.start()
                scheduleCursor(ticket, prepared, duration)
            }
            setOnCompletionListener { completed ->
                if (player === completed) player = null
                runCatching { completed.release() }
                file.delete()
                if (ticket == generation.get() && !released) callbacks.onSpeechFinished()
            }
            setOnErrorListener { failed, _, _ ->
                if (player === failed) player = null
                runCatching { failed.release() }
                file.delete()
                if (ticket == generation.get() && !released) callbacks.onError("تعذر تشغيل الرد الصوتي.")
                true
            }
        }
        player = mediaPlayer
        mediaPlayer.prepareAsync()
    }

    private fun scheduleCursor(ticket: Long, active: MediaPlayer, durationMs: Int) {
        main.post(object : Runnable {
            override fun run() {
                if (ticket != generation.get() || player !== active || released) return
                val current = runCatching { active.currentPosition }.getOrDefault(0)
                callbacks.onSpeechCursor((current.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f))
                if (runCatching { active.isPlaying }.getOrDefault(false)) {
                    main.postDelayed(this, CURSOR_INTERVAL_MS)
                }
            }
        })
    }

    private fun stopPlayer() {
        val active = player ?: return
        player = null
        runCatching { active.stop() }
        runCatching { active.reset() }
        runCatching { active.release() }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "AlShorti-Android/0.3")
        }

    private fun ensureSuccess(connection: HttpURLConnection, prefix: String) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = runCatching {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty().take(180)
            error("$prefix ($code)${if (detail.isBlank()) "" else ": $detail"}")
        }
    }

    private fun parseServerError(data: String): String = runCatching {
        JSONObject(data).optString("message").takeIf { it.isNotBlank() }
    }.getOrNull() ?: "خدمة الصوت السعودي أرجعت خطأ."

    private fun normalizeForSpeech(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace("…", "،")
        .trim()

    private data class AudioLocation(val url: String?, val path: String?)

    private companion object {
        const val BASE_URL = "https://omarelshehy-namaa-saudi-voice.hf.space"
        const val API_NAME = "generate_tts_audio"
        const val MAX_TEXT_CHARS = 220
        const val EXAGGERATION = 0.55
        const val TEMPERATURE = 0.72
        const val CFG_WEIGHT = 0.35
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 120_000
        const val EVENT_READ_TIMEOUT_MS = 180_000
        const val BUFFER_SIZE = 128 * 1024
        const val CURSOR_INTERVAL_MS = 55L
    }
}
