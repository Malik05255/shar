package com.malik.alshurti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.malik.alshurti.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Saudi production speech path backed by Microsoft Azure Speech.
 *
 * Police uses the native Saudi male voice ar-SA-HamedNeural by default. Staff uses
 * ar-SA-ZariyahNeural by default so background characters sound distinct. There is no Android TTS
 * fallback: if Azure cannot synthesize a valid audio file the conversation does not pretend that
 * speech succeeded.
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
    private val voiceCacheDir = File(appContext.cacheDir, "azure-saudi-voice-v1").apply { mkdirs() }

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
            if (role == VoiceRole.POLICE) "alshorti-azure-police" else "alshorti-azure-staff"
        ).apply {
            priority = if (role == VoiceRole.POLICE) Thread.NORM_PRIORITY + 1 else Thread.NORM_PRIORITY
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                reportError(throwable.message)
            }
        }
    }

    fun prepare() {
        if (released) return
        pruneVoiceCache()

        val configurationError = validateConfiguration()
        if (configurationError != null) {
            reportError(configurationError)
            return
        }

        val ticket = generation.incrementAndGet()
        dispatch { callbacks.onPreparing(10, "") }
        try {
            networkExecutor.execute {
                runCatching {
                    cachedOrSynthesize(ticket, prewarmText()) ?: return@runCatching
                    if (ticket == generation.get() && !released) {
                        dispatch {
                            callbacks.onPreparing(100, "")
                            callbacks.onReady()
                        }
                    }
                }.onFailure { throwable ->
                    if (ticket == generation.get() && !released) reportError(throwable.message)
                }
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

        val configurationError = validateConfiguration()
        if (configurationError != null) {
            reportError(configurationError)
            return
        }

        val ticket = generation.incrementAndGet()
        mainHandler.post { stopPlayback() }
        dispatch { callbacks.onPreparing(0, "") }

        try {
            networkExecutor.execute {
                runCatching {
                    val audioFile = cachedOrSynthesize(ticket, normalized) ?: return@runCatching
                    if (ticket != generation.get() || released) return@runCatching
                    dispatch { callbacks.onPreparing(100, "") }
                    mainHandler.post {
                        if (ticket == generation.get() && !released) {
                            startPlaybackSafely(ticket, audioFile)
                        }
                    }
                }.onFailure { throwable ->
                    if (ticket == generation.get() && !released) reportError(throwable.message)
                }
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
        if (BuildConfig.AZURE_SPEECH_KEY.trim().isBlank()) {
            return "الصوت السعودي غير مهيأ في هذه النسخة."
        }
        if (BuildConfig.AZURE_SPEECH_REGION.trim().isBlank()) {
            return "منطقة خدمة الصوت السعودي غير مهيأة."
        }
        if (configuredVoice().isBlank()) {
            return "لم يتم تحديد الصوت السعودي."
        }
        return null
    }

    private fun configuredVoice(): String = when (role) {
        VoiceRole.POLICE -> BuildConfig.AZURE_POLICE_VOICE.trim()
        VoiceRole.STAFF -> BuildConfig.AZURE_STAFF_VOICE.trim()
    }

    private fun prewarmText(): String = when (role) {
        VoiceRole.POLICE -> "هلا يا بطل، معك الشرطي. وش عندك؟"
        VoiceRole.STAFF -> "سيدي، الملف جاهز."
    }

    private fun cachedOrSynthesize(ticket: Long, text: String): File? {
        val voice = configuredVoice()
        val region = BuildConfig.AZURE_SPEECH_REGION.trim()
        val destination = cacheFile(region, voice, text)
        if (destination.exists() && destination.length() >= MIN_AUDIO_BYTES) {
            destination.setLastModified(System.currentTimeMillis())
            return destination
        }

        val endpoint = URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Ocp-Apim-Subscription-Key", BuildConfig.AZURE_SPEECH_KEY.trim())
            setRequestProperty("Content-Type", "application/ssml+xml")
            setRequestProperty("X-Microsoft-OutputFormat", AZURE_OUTPUT_FORMAT)
            setRequestProperty("Accept", "audio/mpeg")
            setRequestProperty("User-Agent", "AlShorti-Android/${BuildConfig.VERSION_NAME}")
        }

        val partial = File(destination.parentFile, "${destination.name}.part")
        runCatching { partial.delete() }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(buildSsml(text, voice))
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                val requestId = connection.getHeaderField("X-RequestId").orEmpty()
                val reason = when (status) {
                    400 -> "خدمة الصوت رفضت النص أو إعداد الصوت السعودي."
                    401 -> "مفتاح Azure Speech غير صالح."
                    403 -> "حساب Azure Speech لا يملك صلاحية لهذا الطلب."
                    404 -> "منطقة Azure Speech أو نقطة الخدمة غير صحيحة."
                    408 -> "خدمة الصوت تأخرت في الاستجابة."
                    429 -> "تم بلوغ حد استخدام خدمة الصوت مؤقتاً."
                    in 500..599 -> "خدمة الصوت السعودي غير متاحة مؤقتاً."
                    else -> "خدمة الصوت أعادت خطأ $status."
                }
                throw IllegalStateException(
                    if (requestId.isBlank()) reason else "$reason [$requestId]"
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
                throw IllegalStateException("وصل ملف الصوت السعودي ناقصاً.")
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

    private fun buildSsml(text: String, voice: String): String {
        val safeText = escapeXml(text)
        val safeVoice = escapeXml(voice)
        val prosody = if (role == VoiceRole.POLICE) {
            "rate='-3%' pitch='-1%'"
        } else {
            "rate='0%' pitch='0%'"
        }
        return """
            <speak version='1.0' xml:lang='ar-SA'>
              <voice xml:lang='ar-SA' name='$safeVoice'>
                <prosody $prosody>$safeText</prosody>
              </voice>
            </speak>
        """.trimIndent()
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun startPlaybackSafely(ticket: Long, audioFile: File) {
        runCatching { startPlayback(ticket, audioFile) }
            .onFailure { throwable ->
                if (ticket == generation.get() && !released) reportError(throwable.message)
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
                    if (ticket != generation.get() || released) {
                        releasePlayer(prepared)
                        return@runCatching
                    }
                    if (!requestAudioFocus()) {
                        throw IllegalStateException("تعذر الحصول على أولوية الصوت.")
                    }

                    val duration = prepared.duration.coerceAtLeast(1)
                    dispatch { callbacks.onSpeechStarted(duration.toLong()) }
                    prepared.start()
                    rampToTargetVolume(ticket, prepared)
                    scheduleCursor(ticket, prepared, duration)
                }.onFailure { throwable ->
                    if (mediaPlayer === prepared) mediaPlayer = null
                    releasePlayer(prepared)
                    abandonAudioFocus()
                    if (ticket == generation.get() && !released) reportError(throwable.message)
                }
            }

            player.setOnCompletionListener { completed ->
                if (ticket == generation.get() && !released) {
                    dispatch {
                        callbacks.onSpeechCursor(1f)
                        callbacks.onSpeechFinished()
                    }
                }
                if (mediaPlayer === completed) mediaPlayer = null
                releasePlayer(completed)
                abandonAudioFocus()
            }

            player.setOnErrorListener { failed, _, _ ->
                if (mediaPlayer === failed) mediaPlayer = null
                releasePlayer(failed)
                abandonAudioFocus()
                if (ticket == generation.get() && !released) {
                    reportError("تعذر تشغيل ملف الصوت السعودي.")
                }
                true
            }

            mediaPlayer = player
            player.prepareAsync()
        } catch (t: Throwable) {
            if (mediaPlayer === player) mediaPlayer = null
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

    private fun targetVolume(): Float = if (role == VoiceRole.POLICE) 1f else 0.78f

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
                    val fraction = (player.currentPosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    dispatch { callbacks.onSpeechCursor(fraction) }
                    if (player.isPlaying || pausedForFocus) mainHandler.postDelayed(this, 36L)
                }.onFailure { throwable ->
                    if (mediaPlayer === player) mediaPlayer = null
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
        if (player != null) releasePlayer(player)
        abandonAudioFocus()
    }

    private fun releasePlayer(player: MediaPlayer) {
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private fun cacheFile(region: String, voice: String, text: String): File {
        val payload = "$CACHE_SCHEMA|${role.name}|$region|$voice|$AZURE_OUTPUT_FORMAT|$text"
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
        dispatch {
            callbacks.onError(
                message ?: if (role == VoiceRole.POLICE) {
                    "تعذر تشغيل الصوت السعودي الطبيعي."
                } else {
                    "تعذر تشغيل صوت موظف المكتب."
                }
            )
        }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(block)
        } else {
            mainHandler.post { runCatching(block) }
        }
    }

    private fun normalizeSaudiText(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace("…", "،")
        .replace("..", ".")
        .trim()

    private companion object {
        const val AZURE_OUTPUT_FORMAT = "audio-24khz-96kbitrate-mono-mp3"
        const val CACHE_SCHEMA = "azure-v1"
        const val MIN_AUDIO_BYTES = 2_048L
        const val MAX_CACHE_FILES = 48
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
        const val VOLUME_RAMP_STEPS = 7
        const val VOLUME_RAMP_STEP_MS = 12L
    }
}
