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
 * This talks to the public NAMAA Saudi Voice Gradio Space. Public ZeroGPU Spaces can
 * queue, sleep, rate-limit or exhaust anonymous quota, so every network stage is
 * surfaced to the UI instead of failing as silent audio.
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
    @Volatile private var cachedApiName: String? = null

    fun prepare() {
        if (released) return
        callbacks.onPreparing(100, "الصوت السعودي جاهز للاختبار")
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
        callbacks.onPreparing(5, "جاري الاتصال بخدمة الصوت السعودي…")

        io.execute {
            try {
                val audio = synthesize(ticket, clean)
                if (ticket != generation.get() || released) {
                    audio.delete()
                    return@execute
                }
                main.post {
                    callbacks.onPreparing(95, "تم استلام الصوت — جاري تشغيله…")
                    play(ticket, audio)
                }
            } catch (t: Throwable) {
                if (ticket == generation.get() && !released) {
                    val detail = when (t) {
                        is HttpStatusException -> when (t.code) {
                            401, 403 -> "خدمة NAMAA رفضت الطلب (${t.code}). قد تتطلب هوية Hugging Face/حصة ZeroGPU."
                            404 -> "تعذر العثور على واجهة NAMAA الصوتية (404)."
                            429 -> "حصة NAMAA/ZeroGPU المجانية مشغولة أو انتهت مؤقتاً (429)."
                            503 -> "خدمة NAMAA تستيقظ أو لا يوجد GPU متاح الآن (503)."
                            else -> "خدمة NAMAA أرجعت HTTP ${t.code}: ${t.detail.take(120)}"
                        }
                        else -> t.message ?: "تعذر تشغيل الصوت السعودي الآن."
                    }
                    main.post { callbacks.onError(detail) }
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
        postPreparing(12, "جاري فحص واجهة NAMAA…")
        val apiName = resolveApiName()
        postPreparing(20, "جاري إرسال النص للصوت السعودي…")
        val eventId = createJob(text, apiName)
        postPreparing(35, "دخل طلب الصوت الطابور…")
        val output = waitForAudio(eventId, apiName, ticket)
        if (ticket != generation.get()) error("cancelled")
        postPreparing(80, "تم توليد الصوت — جاري تنزيله…")
        return downloadAudio(output, ticket)
    }

    private fun resolveApiName(): String {
        cachedApiName?.let { return it }

        val discovered = runCatching {
            val connection = openConnection("$BASE_URL/gradio_api/info", "GET")
            try {
                ensureSuccess(connection, "تعذر قراءة واجهة Gradio")
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val named = root.optJSONObject("named_endpoints")
                if (named != null) {
                    val keys = named.keys().asSequence().toList()
                    keys.firstOrNull { it.contains("generate_tts_audio", ignoreCase = true) }
                        ?: keys.firstOrNull { it.contains("predict", ignoreCase = true) }
                } else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        val normalized = discovered
            ?.trim()
            ?.trimStart('/')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_API_NAME
        cachedApiName = normalized
        return normalized
    }

    private fun createJob(text: String, preferredApiName: String): String {
        val candidates = linkedSetOf(preferredApiName, DEFAULT_API_NAME, "predict")
        var last404: HttpStatusException? = null
        for (apiName in candidates) {
            try {
                val event = createJobAt(text, apiName)
                cachedApiName = apiName
                return event
            } catch (error: HttpStatusException) {
                if (error.code == 404) {
                    last404 = error
                    continue
                }
                throw error
            }
        }
        throw last404 ?: error("تعذر العثور على واجهة توليد الصوت.")
    }

    private fun createJobAt(text: String, apiName: String): String {
        val payload = JSONObject().apply {
            put(
                "data",
                JSONArray().apply {
                    put(text.take(MAX_TEXT_CHARS))
                    put(JSONObject.NULL)
                    put(EXAGGERATION)
                    put(TEMPERATURE)
                    put(0)
                    put(CFG_WEIGHT)
                }
            )
        }

        val connection = openConnection("$BASE_URL/gradio_api/call/$apiName", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
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

    private fun waitForAudio(eventId: String, apiName: String, ticket: Long): AudioLocation {
        val connection = openConnection("$BASE_URL/gradio_api/call/$apiName/$eventId", "GET").apply {
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
                        line.startsWith("event:") -> {
                            event = line.substringAfter(':').trim()
                            if (event == "generating") postPreparing(55, "NAMAA يولّد الصوت الآن…")
                        }
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
            val directUrl = value.optString("url").takeIf { it.isNotBlank() }
            if (directUrl != null) {
                AudioLocation(url = directUrl, path = null)
            } else {
                val directPath = value.optString("path").takeIf { it.isNotBlank() }
                if (directPath != null) {
                    AudioLocation(url = null, path = directPath)
                } else {
                    value.keys().asSequence()
                        .mapNotNull { key -> findAudioRecursive(value.opt(key)) }
                        .firstOrNull()
                }
            }
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { index -> findAudioRecursive(value.opt(index)) }
            .firstOrNull()
        is String -> when {
            value.startsWith("http://") || value.startsWith("https://") -> AudioLocation(value, null)
            value.contains("/gradio_api/file=") -> AudioLocation(value, null)
            value.endsWith(".wav") || value.endsWith(".flac") || value.endsWith(".mp3") -> AudioLocation(null, value)
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
            setVolume(1f, 1f)
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
            setOnErrorListener { failed, what, extra ->
                if (player === failed) player = null
                runCatching { failed.release() }
                file.delete()
                if (ticket == generation.get() && !released) {
                    callbacks.onError("تعذر تشغيل ملف الصوت على الهاتف (MediaPlayer $what/$extra).")
                }
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
            setRequestProperty("User-Agent", "AlShorti-Android/0.3.2")
            setRequestProperty("Accept-Language", "ar-SA,ar;q=0.9,en;q=0.5")
        }

    private fun ensureSuccess(connection: HttpURLConnection, prefix: String) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = runCatching {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            throw HttpStatusException(code, "$prefix: $detail")
        }
    }

    private fun parseServerError(data: String): String = runCatching {
        JSONObject(data).optString("message").takeIf { it.isNotBlank() }
    }.getOrNull() ?: "خدمة الصوت السعودي أرجعت خطأ."

    private fun normalizeForSpeech(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace("…", "،")
        .trim()

    private fun postPreparing(percent: Int, message: String) {
        main.post {
            if (!released) callbacks.onPreparing(percent, message)
        }
    }

    private data class AudioLocation(val url: String?, val path: String?)
    private class HttpStatusException(val code: Int, val detail: String) : RuntimeException(detail)

    private companion object {
        const val BASE_URL = "https://omarelshehy-namaa-saudi-voice.hf.space"
        const val DEFAULT_API_NAME = "generate_tts_audio"
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
