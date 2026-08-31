package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.malik.alshurti.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * High-naturalness Saudi speech path backed by Gemini 3.1 Flash TTS.
 *
 * Gemini currently returns mono 16-bit little-endian PCM at 24 kHz. Playback deliberately uses
 * AudioTrack directly instead of WAV + MediaPlayer so OEM media decoders/audio-focus quirks cannot
 * turn a valid generated utterance into silence.
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

    private data class AudioPayload(
        val data: String,
        val mimeType: String,
        val sampleRate: Int
    )

    private data class PcmClip(
        val file: File,
        val sampleRate: Int
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val voiceCacheDir = File(appContext.cacheDir, "gemini-saudi-voice-v4-pcm").apply { mkdirs() }

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusHeld = false
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var released = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            val track = audioTrack ?: return@post
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> runCatching { track.setVolume(targetVolume()) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    runCatching { track.setVolume(targetVolume() * 0.28f) }
                AudioManager.AUDIOFOCUS_LOSS -> runCatching { track.setVolume(0f) }
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(speechAttributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
        .build()

    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, if (role == VoiceRole.POLICE) "alshorti-gemini-police" else "alshorti-gemini-staff").apply {
            priority = if (role == VoiceRole.POLICE) Thread.NORM_PRIORITY + 1 else Thread.NORM_PRIORITY
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable -> reportError(throwable.message) }
        }
    }

    private val playbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, if (role == VoiceRole.POLICE) "alshorti-pcm-police" else "alshorti-pcm-staff").apply {
            priority = Thread.NORM_PRIORITY + 1
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
                    if (warmed.file.length() < MIN_PCM_BYTES) throw IllegalStateException("وصل ملف الصوت الطبيعي ناقصاً.")
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
        if (normalized.isBlank()) {
            dispatch(callbacks::onSpeechFinished)
            return
        }
        validateConfiguration()?.let { reportError(it); return }

        val ticket = generation.incrementAndGet()
        stopPlayback()
        dispatch { callbacks.onPreparing(0, "") }
        try {
            networkExecutor.execute {
                runCatching {
                    val clip = cachedOrSynthesize(ticket, normalized) ?: return@runCatching
                    if (ticket != generation.get() || released) return@runCatching
                    dispatch { callbacks.onPreparing(100, "") }
                    startPlaybackSafely(ticket, clip)
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
        stopPlayback()
    }

    fun release() {
        released = true
        generation.incrementAndGet()
        stopPlayback()
        runCatching { networkExecutor.shutdownNow() }
        runCatching { playbackExecutor.shutdownNow() }
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

    private fun cachedOrSynthesize(ticket: Long, text: String): PcmClip? {
        val voice = configuredVoice()
        val prompt = directorPrompt(text)
        val destination = cacheFile(voice, prompt)
        val rateFile = File(destination.parentFile, "${destination.name}.rate")
        if (destination.exists() && destination.length() >= MIN_PCM_BYTES) {
            destination.setLastModified(System.currentTimeMillis())
            val rate = rateFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: PCM_SAMPLE_RATE
            return PcmClip(destination, rate)
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
            val providerBytes = runCatching { Base64.decode(payload.data, Base64.DEFAULT) }
                .getOrElse { throw IllegalStateException("تعذر فك بيانات الصوت من Gemini.") }
            if (providerBytes.size < MIN_PROVIDER_BYTES) throw IllegalStateException("وصل الصوت من Gemini ناقصاً.")

            val decoded = decodeToPcm(providerBytes, payload)
            if (decoded.first.size < MIN_PCM_BYTES) throw IllegalStateException("وصل الصوت من Gemini ناقصاً.")
            partial.writeBytes(decoded.first)
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            rateFile.writeText(decoded.second.toString())
            destination.setLastModified(System.currentTimeMillis())
            pruneVoiceCache()
            return PcmClip(destination, decoded.second)
        } finally {
            connection.disconnect()
            runCatching { partial.delete() }
        }
    }

    private fun decodeToPcm(bytes: ByteArray, payload: AudioPayload): Pair<ByteArray, Int> {
        val mime = payload.mimeType.lowercase()
        if (mime.startsWith("audio/l16") || mime.startsWith("audio/pcm") || mime.isBlank()) {
            val rate = parseRateFromMime(mime) ?: payload.sampleRate.takeIf { it > 0 } ?: PCM_SAMPLE_RATE
            val evenLength = bytes.size - (bytes.size % 2)
            return bytes.copyOf(evenLength) to rate
        }
        if (mime.startsWith("audio/wav") || looksLikeWav(bytes)) return decodeWavPcm(bytes)
        throw IllegalStateException("Gemini أعاد تنسيق صوت غير متوقع: ${payload.mimeType}")
    }

    /** Parse simple PCM16 mono WAV if the provider starts returning WAV directly in the future. */
    private fun decodeWavPcm(bytes: ByteArray): Pair<ByteArray, Int> {
        if (!looksLikeWav(bytes)) throw IllegalStateException("ملف WAV غير صالح.")
        var offset = 12
        var sampleRate = PCM_SAMPLE_RATE
        var channels = 1
        var bitsPerSample = 16
        var audioFormat = 1
        var data: ByteArray? = null

        fun le16(at: Int): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
        fun le32(at: Int): Int = (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)

        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = le32(offset + 4).coerceAtLeast(0)
            val start = offset + 8
            val end = (start + size).coerceAtMost(bytes.size)
            if (start > bytes.size) break
            when (id) {
                "fmt " -> if (size >= 16 && start + 16 <= bytes.size) {
                    audioFormat = le16(start)
                    channels = le16(start + 2)
                    sampleRate = le32(start + 4)
                    bitsPerSample = le16(start + 14)
                }
                "data" -> data = bytes.copyOfRange(start, end)
            }
            offset = end + (size and 1)
        }

        if (audioFormat != 1 || channels != 1 || bitsPerSample != 16 || data == null) {
            throw IllegalStateException("تنسيق WAV غير مدعوم للتشغيل المباشر.")
        }
        return data!! to sampleRate.coerceAtLeast(8_000)
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
        val explicitRate = node.optInt("sample_rate", node.optInt("sampleRate", 0))
        val rate = explicitRate.takeIf { it > 0 } ?: parseRateFromMime(mimeType) ?: PCM_SAMPLE_RATE
        return AudioPayload(data, mimeType, rate)
    }

    private fun findAudioPayload(node: Any?): AudioPayload? = when (node) {
        is JSONObject -> audioPayloadFrom(node) ?: node.keys().asSequence().mapNotNull { findAudioPayload(node.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until node.length()).asSequence().mapNotNull { findAudioPayload(node.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun parseRateFromMime(mime: String): Int? =
        Regex("(?:rate|sample_rate)\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(mime)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun looksLikeWav(bytes: ByteArray): Boolean = bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() && bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()

    private fun compactProviderError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.take(180).orEmpty()
    }.getOrDefault("")

    private fun startPlaybackSafely(ticket: Long, clip: PcmClip) {
        try {
            playbackExecutor.execute {
                runCatching { startPlayback(ticket, clip) }.onFailure {
                    if (ticket == generation.get() && !released) reportError(it.message)
                }
            }
        } catch (_: RejectedExecutionException) {
            reportError("تعذر تشغيل الصوت الطبيعي.")
        }
    }

    private fun startPlayback(ticket: Long, clip: PcmClip) {
        stopPlayback()
        if (ticket != generation.get() || released) return
        val pcm = clip.file.readBytes()
        if (pcm.size < MIN_PCM_BYTES) throw IllegalStateException("ملف الصوت ناقص.")

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(clip.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            clip.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).takeIf { it > 0 } ?: 0
        val bufferBytes = maxOf(pcm.size, minBuffer)

        val track = AudioTrack.Builder()
            .setAudioAttributes(speechAttributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("تعذر فتح سماعة الصوت.")
        }

        audioTrack = track
        try {
            val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            if (written < pcm.size) throw IllegalStateException("تعذر تجهيز الصوت للسماعة.")
            if (ticket != generation.get() || released || audioTrack !== track) return

            // Focus improves clarity when another app is playing audio, but a denied focus request
            // must never make a perfectly valid TTS utterance silent.
            requestAudioFocus()
            track.setVolume(0f)
            val totalFrames = pcm.size / 2
            val durationMs = ((totalFrames * 1_000L) / clip.sampleRate.toLong()).coerceAtLeast(1L)
            dispatch { callbacks.onSpeechStarted(durationMs) }
            track.play()
            rampToTargetVolume(ticket, track)

            while (ticket == generation.get() && !released && audioTrack === track) {
                val played = track.playbackHeadPosition.toLong().coerceAtLeast(0L)
                val fraction = (played.toDouble() / totalFrames.toDouble()).toFloat().coerceIn(0f, 1f)
                dispatch { callbacks.onSpeechCursor(fraction) }
                if (played >= totalFrames - 2L) break
                try {
                    Thread.sleep(CURSOR_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }

            if (ticket == generation.get() && !released && audioTrack === track) {
                dispatch {
                    callbacks.onSpeechCursor(1f)
                    callbacks.onSpeechFinished()
                }
            }
        } finally {
            if (audioTrack === track) audioTrack = null
            releaseTrack(track)
            abandonAudioFocus()
        }
    }

    private fun rampToTargetVolume(ticket: Long, track: AudioTrack) {
        val target = targetVolume()
        repeat(VOLUME_RAMP_STEPS) { index ->
            mainHandler.postDelayed({
                if (ticket != generation.get() || audioTrack !== track || released) return@postDelayed
                val gain = target * ((index + 1f) / VOLUME_RAMP_STEPS.toFloat())
                runCatching { track.setVolume(gain) }
            }, index * VOLUME_RAMP_STEP_MS)
        }
    }

    private fun targetVolume(): Float = if (role == VoiceRole.POLICE) 1f else 0.82f

    private fun requestAudioFocus() {
        val result = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!focusHeld) return
        focusHeld = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private fun stopPlayback() {
        val track = audioTrack
        audioTrack = null
        if (track != null) releaseTrack(track)
        abandonAudioFocus()
    }

    private fun releaseTrack(track: AudioTrack) {
        runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    private fun cacheFile(voice: String, prompt: String): File {
        val payload = "$CACHE_SCHEMA|${role.name}|$GEMINI_TTS_MODEL|$voice|$prompt"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(voiceCacheDir, "$digest.pcm")
    }

    private fun pruneVoiceCache() {
        runCatching {
            val files = voiceCacheDir.listFiles { file -> file.isFile && file.extension == "pcm" }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            var totalBytes = 0L
            files.forEachIndexed { index, file ->
                totalBytes += file.length()
                if (index >= MAX_CACHE_FILES || totalBytes > MAX_CACHE_BYTES) {
                    runCatching { file.delete() }
                    runCatching { File(file.parentFile, "${file.name}.rate").delete() }
                }
            }
            voiceCacheDir.listFiles { file -> file.name.endsWith(".part") }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun reportError(message: String?) {
        if (released) return
        dispatch { callbacks.onError(message?.takeIf { it.isNotBlank() } ?: "تعذر تشغيل الصوت السعودي الطبيعي.") }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) runCatching(block)
        else mainHandler.post { runCatching(block) }
    }

    private fun normalizeSaudiText(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace("…", "،")
        .trim()

    private companion object {
        const val GEMINI_TTS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val GEMINI_API_REVISION = "2026-05-20"
        const val GEMINI_TTS_MODEL = "gemini-3.1-flash-tts-preview"
        const val CACHE_SCHEMA = "gemini-saudi-v4-pcm"
        const val PCM_SAMPLE_RATE = 24_000
        const val MIN_PROVIDER_BYTES = 3_000
        const val MIN_PCM_BYTES = 3_000
        const val MAX_CACHE_FILES = 40
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
        const val VOLUME_RAMP_STEPS = 8
        const val VOLUME_RAMP_STEP_MS = 12L
        const val CURSOR_INTERVAL_MS = 36L
    }
}
