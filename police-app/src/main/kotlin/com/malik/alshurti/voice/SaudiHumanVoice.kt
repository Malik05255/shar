package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.malik.alshurti.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** High-naturalness Saudi speech path backed by Gemini 3.1 Flash TTS. */
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

    private data class AudioPayload(val data: String, val mimeType: String, val sampleRate: Int)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val voiceCacheDir = File(appContext.cacheDir, "gemini-saudi-voice-v3").apply { mkdirs() }

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusHeld = false
    private var pausedForFocus = false
    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var released = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            val player = mediaPlayer ?: return@post
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus && !released) {
                    pausedForFocus = false
                    runCatching { restoreTargetVolume(player); player.start() }
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
        Thread(runnable, if (role == VoiceRole.POLICE) "alshorti-gemini-police" else "alshorti-gemini-staff").apply {
            priority = if (role == VoiceRole.POLICE) Thread.NORM_PRIORITY + 1 else Thread.NORM_PRIORITY
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable -> reportError(throwable.message) }
        }
    }

    fun prepare() {
        if (released) return
        pruneVoiceCache()
        validateConfiguration()?.let { reportError(it); return }
        val ticket = generation.incrementAndGet()
        dispatch { callbacks.onPreparing(10, "") }
        try {
            networkExecutor.execute {
                runCatching {
                    val warmed = cachedOrSynthesize(ticket, prewarmText()) ?: return@runCatching
                    if (warmed.length() < MIN_AUDIO_BYTES) throw IllegalStateException("وصل ملف الصوت الطبيعي ناقصاً.")
                    if (ticket == generation.get() && !released) dispatch {
                        callbacks.onPreparing(100, "")
                        callbacks.onReady()
                    }
                }.onFailure { if (ticket == generation.get() && !released) reportError(it.message) }
            }
        } catch (_: RejectedExecutionException) {
            reportError(null)
        }
    }

    fun speak(text: String) {
        if (released) return
        val normalized = normalizeSaudiText(text)
        if (normalized.isBlank()) { dispatch(callbacks::onSpeechFinished); return }
        validateConfiguration()?.let { reportError(it); return }
        val ticket = generation.incrementAndGet()
        mainHandler.post { stopPlayback() }
        dispatch { callbacks.onPreparing(0, "") }
        try {
            networkExecutor.execute {
                runCatching {
                    val audioFile = cachedOrSynthesize(ticket, normalized) ?: return@runCatching
                    if (ticket != generation.get() || released) return@runCatching
                    dispatch { callbacks.onPreparing(100, "") }
                    mainHandler.post { if (ticket == generation.get() && !released) startPlaybackSafely(ticket, audioFile) }
                }.onFailure { if (ticket == generation.get() && !released) reportError(it.message) }
            }
        } catch (_: RejectedExecutionException) {
            reportError(null)
        } catch (t: Throwable) {
            reportError(t.message)
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

    private fun validateConfiguration(): String? {
        if (BuildConfig.GEMINI_API_KEY.trim().isBlank()) return "الصوت الطبيعي غير مهيأ في هذه النسخة."
        if (configuredVoice().isBlank()) return "لم يتم تحديد صوت الشخصية."
        return null
    }

    private fun configuredVoice(): String = when (role) {
        VoiceRole.POLICE -> BuildConfig.GEMINI_POLICE_VOICE.trim()
        VoiceRole.STAFF -> BuildConfig.GEMINI_STAFF_VOICE.trim()
    }

    private fun prewarmText(): String = when (role) {
        VoiceRole.POLICE -> "هلا يا بطل، معك الشرطي. وش عندك؟"
        VoiceRole.STAFF -> "سيدي، الملف جاهز."
    }

    private fun cachedOrSynthesize(ticket: Long, text: String): File? {
        val voice = configuredVoice()
        val prompt = directorPrompt(text)
        val destination = cacheFile(voice, prompt)
        if (destination.exists() && destination.length() >= MIN_AUDIO_BYTES) {
            destination.setLastModified(System.currentTimeMillis())
            return destination
        }

        val connection = (URL(GEMINI_TTS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY.trim())
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Api-Revision", GEMINI_API_REVISION)
            setRequestProperty("User-Agent", "AlShorti-Android/${BuildConfig.VERSION_NAME}")
        }

        val requestBody = JSONObject().apply {
            put("model", GEMINI_TTS_MODEL)
            put("input", prompt)
            put("response_format", JSONObject().put("type", "audio"))
            put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        }.toString()

        val partial = File(destination.parentFile, "${destination.name}.part")
        runCatching { partial.delete() }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            val status = connection.responseCode
            val responseText = if (status in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            if (status !in 200..299) {
                val reason = when (status) {
                    400 -> "Gemini رفض إعداد توليد الصوت."
                    401 -> "مفتاح Gemini غير صالح."
                    403 -> "مفتاح Gemini لا يملك صلاحية توليد الصوت."
                    404 -> "نموذج الصوت غير متاح لهذا الحساب."
                    429 -> "تم بلوغ حد استخدام Gemini مؤقتاً."
                    in 500..599 -> "خدمة Gemini الصوتية غير متاحة مؤقتاً."
                    else -> "خدمة الصوت أعادت خطأ $status."
                }
                throw IllegalStateException(if (responseText.isBlank()) reason else "$reason ${compactProviderError(responseText)}")
            }
            if (ticket != generation.get() || released) return null

            val payload = extractAudioPayload(responseText)
                ?: throw IllegalStateException("Gemini لم يُرجع مساراً صوتياً صالحاً.")
            val bytes = runCatching { Base64.decode(payload.data, Base64.DEFAULT) }
                .getOrElse { throw IllegalStateException("تعذر فك بيانات الصوت من Gemini.") }
            if (bytes.size < MIN_PROVIDER_BYTES) throw IllegalStateException("وصل الصوت من Gemini ناقصاً.")

            val mime = payload.mimeType.lowercase()
            when {
                mime.startsWith("audio/wav") || looksLikeWav(bytes) -> {
                    FileOutputStream(partial).buffered(128 * 1024).use { out -> out.write(bytes); out.flush() }
                }
                mime.startsWith("audio/l16") || mime.startsWith("audio/pcm") || mime.isBlank() -> {
                    writePcm16MonoWav(partial, bytes, payload.sampleRate.takeIf { it > 0 } ?: PCM_SAMPLE_RATE)
                }
                else -> throw IllegalStateException("Gemini أعاد تنسيق صوت غير متوقع: ${payload.mimeType}")
            }

            if (partial.length() < MIN_AUDIO_BYTES) {
                partial.delete()
                throw IllegalStateException("تعذر تجهيز ملف الصوت الطبيعي.")
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

    private fun directorPrompt(text: String): String {
        val direction = if (role == VoiceRole.POLICE) {
            "Native Saudi man from Riyadh. Natural conversational Najdi/Saudi accent. Mature calm presence, warm with children, confident but never theatrical. Speak like a real Saudi person nearby, not a broadcaster. Preserve colloquial Saudi wording. Medium-low pitch, relaxed pace, short natural pauses, subtle breathing, no announcer cadence, no exaggerated emotion."
        } else {
            "Native Saudi woman from Riyadh. Natural conversational Saudi accent. Professional office colleague, warm and realistic, relaxed pace, no broadcaster style, no exaggerated emotion."
        }
        return "Generate speech audio only. Do not speak or paraphrase these directions.\nVoice direction: $direction\nSpeak exactly the transcript after [TRANSCRIPT].\n[TRANSCRIPT]\n$text"
    }

    private fun extractAudioPayload(responseText: String): AudioPayload? {
        val root = JSONObject(responseText)
        root.optJSONArray("steps")?.let { steps ->
            for (stepIndex in 0 until steps.length()) {
                val step = steps.optJSONObject(stepIndex) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    audioPayloadFrom(content.optJSONObject(contentIndex))?.let { return it }
                }
            }
        }
        audioPayloadFrom(root.optJSONObject("output_audio"))?.let { return it }
        audioPayloadFrom(root.optJSONObject("outputAudio"))?.let { return it }
        return findAudioPayload(root)
    }

    private fun audioPayloadFrom(node: JSONObject?): AudioPayload? {
        node ?: return null
        val data = node.optString("data")
        if (data.isBlank()) return null
        val type = node.optString("type")
        val mimeType = node.optString("mime_type", node.optString("mimeType"))
        if (type != "audio" && !mimeType.startsWith("audio/", ignoreCase = true)) return null
        return AudioPayload(data, mimeType, node.optInt("sample_rate", node.optInt("sampleRate", PCM_SAMPLE_RATE)))
    }

    private fun findAudioPayload(node: Any?): AudioPayload? = when (node) {
        is JSONObject -> audioPayloadFrom(node) ?: node.keys().asSequence().mapNotNull { findAudioPayload(node.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until node.length()).asSequence().mapNotNull { findAudioPayload(node.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun looksLikeWav(bytes: ByteArray): Boolean = bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() && bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()

    private fun writePcm16MonoWav(file: File, pcm: ByteArray, sampleRate: Int) {
        FileOutputStream(file).buffered(64 * 1024).use { out ->
            val dataSize = pcm.size
            fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun le16(value: Int) { out.write(value and 0xff); out.write((value ushr 8) and 0xff) }
            fun le32(value: Int) { out.write(value and 0xff); out.write((value ushr 8) and 0xff); out.write((value ushr 16) and 0xff); out.write((value ushr 24) and 0xff) }
            ascii("RIFF"); le32(dataSize + 36); ascii("WAVE"); ascii("fmt "); le32(16); le16(1); le16(1)
            le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16); ascii("data"); le32(dataSize); out.write(pcm); out.flush()
        }
    }

    private fun compactProviderError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.take(180).orEmpty()
    }.getOrDefault("")

    private fun startPlaybackSafely(ticket: Long, audioFile: File) {
        runCatching { startPlayback(ticket, audioFile) }.onFailure {
            if (ticket == generation.get() && !released) reportError(it.message)
        }
    }

    private fun startPlayback(ticket: Long, audioFile: File) {
        stopPlayback()
        if (released) return
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(speechAttributes)
            player.setDataSource(audioFile.absolutePath)
            player.setVolume(0f, 0f)
            player.setOnPreparedListener { prepared ->
                runCatching {
                    if (ticket != generation.get() || released) { releasePlayer(prepared); return@runCatching }
                    if (!requestAudioFocus()) throw IllegalStateException("تعذر الحصول على أولوية الصوت.")
                    val duration = prepared.duration.coerceAtLeast(1)
                    dispatch { callbacks.onSpeechStarted(duration.toLong()) }
                    prepared.start()
                    rampToTargetVolume(ticket, prepared)
                    scheduleCursor(ticket, prepared, duration)
                }.onFailure {
                    if (mediaPlayer === prepared) mediaPlayer = null
                    releasePlayer(prepared); abandonAudioFocus()
                    if (ticket == generation.get() && !released) reportError(it.message)
                }
            }
            player.setOnCompletionListener { completed ->
                if (ticket == generation.get() && !released) dispatch { callbacks.onSpeechCursor(1f); callbacks.onSpeechFinished() }
                if (mediaPlayer === completed) mediaPlayer = null
                releasePlayer(completed); abandonAudioFocus()
            }
            player.setOnErrorListener { failed, _, _ ->
                if (mediaPlayer === failed) mediaPlayer = null
                releasePlayer(failed); abandonAudioFocus()
                if (ticket == generation.get() && !released) reportError("تعذر تشغيل ملف الصوت الطبيعي.")
                true
            }
            mediaPlayer = player
            player.prepareAsync()
        } catch (t: Throwable) {
            if (mediaPlayer === player) mediaPlayer = null
            releasePlayer(player); abandonAudioFocus(); throw t
        }
    }

    private fun rampToTargetVolume(ticket: Long, player: MediaPlayer) {
        val target = targetVolume()
        repeat(VOLUME_RAMP_STEPS) { index -> mainHandler.postDelayed({
            if (ticket != generation.get() || mediaPlayer !== player || released) return@postDelayed
            val gain = target * ((index + 1f) / VOLUME_RAMP_STEPS.toFloat())
            runCatching { player.setVolume(gain, gain) }
        }, index * VOLUME_RAMP_STEP_MS) }
    }

    private fun restoreTargetVolume(player: MediaPlayer) { val gain = targetVolume(); runCatching { player.setVolume(gain, gain) } }
    private fun targetVolume(): Float = if (role == VoiceRole.POLICE) 1f else 0.80f

    private fun requestAudioFocus(): Boolean {
        val result = runCatching { audioManager.requestAudioFocus(focusRequest) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonAudioFocus() {
        if (!focusHeld) return
        focusHeld = false; pausedForFocus = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private fun scheduleCursor(ticket: Long, player: MediaPlayer, durationMs: Int) {
        val tick = object : Runnable {
            override fun run() {
                if (ticket != generation.get() || mediaPlayer !== player || released) return
                runCatching {
                    val fraction = (player.currentPosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    dispatch { callbacks.onSpeechCursor(fraction) }
                    if (player.isPlaying || pausedForFocus) mainHandler.postDelayed(this, 36L)
                }.onFailure {
                    if (mediaPlayer === player) mediaPlayer = null
                    releasePlayer(player); abandonAudioFocus()
                    if (ticket == generation.get() && !released) reportError(it.message)
                }
            }
        }
        mainHandler.post(tick)
    }

    private fun stopPlayback() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) releasePlayer(player)
        abandonAudioFocus()
    }

    private fun releasePlayer(player: MediaPlayer) {
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private fun cacheFile(voice: String, prompt: String): File {
        val payload = "$CACHE_SCHEMA|${role.name}|$GEMINI_TTS_MODEL|$voice|$prompt"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(voiceCacheDir, "$digest.wav")
    }

    private fun pruneVoiceCache() {
        runCatching {
            val files = voiceCacheDir.listFiles { file -> file.isFile && file.extension == "wav" }?.sortedByDescending { it.lastModified() }.orEmpty()
            var totalBytes = 0L
            files.forEachIndexed { index, file ->
                totalBytes += file.length()
                if (index >= MAX_CACHE_FILES || totalBytes > MAX_CACHE_BYTES) runCatching { file.delete() }
            }
            voiceCacheDir.listFiles { file -> file.name.endsWith(".part") }?.forEach { runCatching { it.delete() } }
        }
    }

    private fun reportError(message: String?) {
        if (released) return
        dispatch { callbacks.onError(message?.takeIf { it.isNotBlank() } ?: "تعذر تشغيل الصوت السعودي الطبيعي.") }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) runCatching(block) else mainHandler.post { runCatching(block) }
    }

    private fun normalizeSaudiText(value: String): String = value.replace(Regex("\\s+"), " ").replace("…", "،").trim()

    private companion object {
        const val GEMINI_TTS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val GEMINI_API_REVISION = "2026-05-20"
        const val GEMINI_TTS_MODEL = "gemini-3.1-flash-tts-preview"
        const val CACHE_SCHEMA = "gemini-saudi-v3"
        const val PCM_SAMPLE_RATE = 24_000
        const val MIN_PROVIDER_BYTES = 3_000
        const val MIN_AUDIO_BYTES = 3_044L
        const val MAX_CACHE_FILES = 40
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
        const val VOLUME_RAMP_STEPS = 8
        const val VOLUME_RAMP_STEP_MS = 12L
    }
}
