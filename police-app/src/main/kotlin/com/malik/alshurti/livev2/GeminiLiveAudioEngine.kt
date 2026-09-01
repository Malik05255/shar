package com.malik.alshurti.livev2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import com.malik.alshurti.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * V2 voice transport.
 *
 * There is deliberately no ASR -> text model -> TTS chain here. A single Gemini Live session owns
 * turn detection, conversation state, native audio generation, interruption and transcription.
 * Android only captures 16 kHz PCM and plays the returned 24 kHz PCM.
 */
class GeminiLiveAudioEngine(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onState(state: State, detail: String? = null)
        fun onUserTranscript(text: String, interim: Boolean)
        fun onModelTranscript(text: String)
        fun onInputLevel(level: Float)
        fun onOutputLevel(level: Float)
    }

    enum class State {
        IDLE,
        CONNECTING,
        READY,
        LISTENING,
        USER_SPEAKING,
        MODEL_SPEAKING,
        ERROR,
        CLOSED
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val captureGeneration = AtomicLong(0L)
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var captureThread: Thread? = null
    private val player = PcmPlayer(listener)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            running.set(false)
            listener.onState(State.ERROR, "GEMINI_API_KEY غير موجود في هذه النسخة.")
            return
        }
        if (!hasMicPermission()) {
            running.set(false)
            listener.onState(State.ERROR, "إذن الميكروفون غير ممنوح.")
            return
        }

        configureCommunicationAudio()
        listener.onState(State.CONNECTING, "فتح جلسة صوتية مباشرة…")
        val url = "$LIVE_ENDPOINT?key=${BuildConfig.GEMINI_API_KEY}"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, socketListener)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        captureGeneration.incrementAndGet()
        stopCapture()
        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
        socket?.close(1000, "client stop")
        socket = null
        player.stopAndClear()
        restoreCommunicationAudio()
        listener.onState(State.CLOSED)
    }

    fun restart() {
        stop()
        start()
    }

    fun release() {
        stop()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        player.release()
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!running.get()) {
                webSocket.close(1000, "stopped")
                return
            }
            val setup = buildSetupMessage()
            if (!webSocket.send(setup)) {
                fail("تعذر إرسال إعداد جلسة Gemini Live.")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!running.get()) return
            runCatching { handleServerMessage(JSONObject(text)) }
                .onFailure { fail(it.message ?: "استجابة Live غير مفهومة.") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!running.get()) return
            fail(t.message ?: "انقطع اتصال Gemini Live.")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (running.getAndSet(false)) {
                stopCapture()
                player.stopAndClear()
                restoreCommunicationAudio()
                listener.onState(State.CLOSED, reason.ifBlank { null })
            }
        }
    }

    private fun buildSetupMessage(): String {
        val voiceName = BuildConfig.GEMINI_POLICE_VOICE.ifBlank { "Gacrux" }
        val speechConfig = JSONObject()
            .put(
                "voiceConfig",
                JSONObject().put(
                    "prebuiltVoiceConfig",
                    JSONObject().put("voiceName", voiceName)
                )
            )

        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", speechConfig)

        val automaticActivity = JSONObject()
            .put("disabled", false)
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
            .put("prefixPaddingMs", 80)
            .put("silenceDurationMs", 420)

        val realtimeInputConfig = JSONObject()
            .put("automaticActivityDetection", automaticActivity)
            .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            .put("turnCoverage", "TURN_INCLUDES_ONLY_ACTIVITY")

        val setup = JSONObject()
            .put("model", "models/$LIVE_MODEL")
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                )
            )
            .put(
                "inputAudioTranscription",
                JSONObject().put("languageCodes", JSONArray().put("ar-SA"))
            )
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", realtimeInputConfig)
            .put("proactivity", JSONObject().put("proactiveAudio", true))
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))

        return JSONObject().put("setup", setup).toString()
    }

    private fun handleServerMessage(root: JSONObject) {
        if (root.has("setupComplete")) {
            listener.onState(State.READY, "Gemini Live متصل")
            startCapture()
            listener.onState(State.LISTENING, "تكلم بشكل طبيعي")
            sendOpeningTurn()
            return
        }

        root.optJSONObject("goAway")?.let {
            listener.onState(State.CONNECTING, "تجديد الجلسة الصوتية…")
        }

        val content = root.optJSONObject("serverContent") ?: return

        if (content.optBoolean("interrupted", false)) {
            player.flush()
            listener.onState(State.USER_SPEAKING, "تمت مقاطعة رد الشرطي")
        }

        content.optJSONObject("interimInputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                listener.onUserTranscript(it, true)
                listener.onState(State.USER_SPEAKING, "أسمعك…")
            }

        content.optJSONObject("inputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let { listener.onUserTranscript(it, false) }

        content.optJSONObject("outputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let { listener.onModelTranscript(it) }

        val modelTurn = content.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val inline = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType", inline.optString("mime_type"))
                if (!mime.startsWith("audio/pcm")) continue
                val encoded = inline.optString("data")
                if (encoded.isBlank()) continue
                val pcm = Base64.decode(encoded, Base64.DEFAULT)
                if (pcm.isNotEmpty()) {
                    listener.onState(State.MODEL_SPEAKING, "الشرطي يرد مباشرة…")
                    player.enqueue(pcm)
                }
            }
        }

        if (content.optBoolean("waitingForInput", false) || content.optBoolean("turnComplete", false)) {
            listener.onState(State.LISTENING, "تكلم… أنا أسمعك")
        }
    }

    private fun sendOpeningTurn() {
        val turns = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "ابدأ المكالمة الآن بتحية سعودية قصيرة جدًا للطفل، ثم اسكت وانتظر أن يتكلم."
                        )
                    )
                )
        )
        socket?.send(
            JSONObject()
                .put("clientContent", JSONObject().put("turns", turns).put("turnComplete", true))
                .toString()
        )
    }

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        if (!running.get() || recorder != null) return
        if (!hasMicPermission()) {
            fail("إذن الميكروفون غير ممنوح.")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            fail("الجهاز لا يدعم إعداد الميكروفون المطلوب.")
            return
        }

        val record = createRecorder(maxOf(minBuffer * 2, INPUT_CHUNK_BYTES * 4)) ?: run {
            fail("تعذر تهيئة الميكروفون.")
            return
        }

        recorder = record
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()?.also {
                runCatching { it.enabled = true }
            }
        }

        val generation = captureGeneration.incrementAndGet()
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            fail("تعذر بدء الميكروفون.")
            return
        }

        captureThread = Thread({ captureLoop(record, generation) }, "alshorti-live-mic").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(bufferBytes: Int): AudioRecord? {
        if (!hasMicPermission()) return null
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(INPUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate
            runCatching { candidate.release() }
        }
        return null
    }

    private fun captureLoop(record: AudioRecord, generation: Long) {
        val buffer = ByteArray(INPUT_CHUNK_BYTES)
        while (running.get() && generation == captureGeneration.get()) {
            val count = runCatching { record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) }
                .getOrElse { -1 }
            if (count <= 0) continue

            val copy = buffer.copyOf(count)
            listener.onInputLevel(pcmLevel(copy))
            val audio = JSONObject()
                .put("data", Base64.encodeToString(copy, Base64.NO_WRAP))
                .put("mimeType", "audio/pcm;rate=$INPUT_RATE")
            val sent = socket?.send(
                JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString()
            ) ?: false
            if (!sent) {
                fail("تعذر إرسال الصوت الحي.")
                break
            }
        }
    }

    private fun stopCapture() {
        captureGeneration.incrementAndGet()
        val active = recorder
        recorder = null
        echoCanceler?.let { runCatching { it.release() } }
        echoCanceler = null
        if (active != null) {
            runCatching {
                if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
            }
            runCatching { active.release() }
        }
        captureThread?.interrupt()
        captureThread = null
        listener.onInputLevel(0f)
    }

    private fun fail(message: String) {
        if (!running.getAndSet(false)) return
        stopCapture()
        player.stopAndClear()
        socket?.cancel()
        socket = null
        restoreCommunicationAudio()
        listener.onState(State.ERROR, message)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun configureCommunicationAudio() {
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val speaker = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) audioManager.setCommunicationDevice(speaker)
            }
        } else {
            runCatching { audioManager.isSpeakerphoneOn = true }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreCommunicationAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }

    private fun pcmLevel(bytes: ByteArray): Float {
        if (bytes.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < bytes.size) {
            val low = bytes[index].toInt() and 0xff
            val high = bytes[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            count += 1
            index += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        return (rms / 7000.0).coerceIn(0.0, 1.0).toFloat()
    }

    private class PcmPlayer(private val listener: Listener) {
        private data class Packet(val generation: Long, val bytes: ByteArray)

        private val queue = LinkedBlockingQueue<Packet>(96)
        private val generation = AtomicLong(0L)
        private val alive = AtomicBoolean(true)
        private val track: AudioTrack = createTrack()
        private val worker = Thread({ playLoop() }, "alshorti-live-speaker").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }

        fun enqueue(bytes: ByteArray) {
            if (!alive.get()) return
            val packet = Packet(generation.get(), bytes)
            if (!queue.offer(packet)) {
                queue.poll()
                queue.offer(packet)
            }
        }

        fun flush() {
            generation.incrementAndGet()
            queue.clear()
            runCatching {
                track.pause()
                track.flush()
                track.play()
            }
            listener.onOutputLevel(0f)
        }

        fun stopAndClear() {
            flush()
        }

        fun release() {
            if (!alive.getAndSet(false)) return
            queue.clear()
            worker.interrupt()
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }

        private fun playLoop() {
            runCatching { track.play() }
            while (alive.get()) {
                val packet = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    continue
                }
                if (packet.generation != generation.get()) continue
                listener.onOutputLevel(level(packet.bytes))
                var offset = 0
                while (offset < packet.bytes.size && packet.generation == generation.get() && alive.get()) {
                    val written = track.write(packet.bytes, offset, packet.bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
                if (queue.isEmpty()) listener.onOutputLevel(0f)
            }
        }

        companion object {
            private fun createTrack(): AudioTrack {
                val min = AudioTrack.getMinBufferSize(
                    OUTPUT_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                return AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUTPUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(min * 4, OUTPUT_RATE * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }

            private fun level(bytes: ByteArray): Float {
                if (bytes.size < 2) return 0f
                var sum = 0.0
                var count = 0
                var i = 0
                while (i + 1 < bytes.size) {
                    val low = bytes[i].toInt() and 0xff
                    val high = bytes[i + 1].toInt()
                    val sample = ((high shl 8) or low).toShort().toInt()
                    sum += sample.toDouble() * sample.toDouble()
                    count++
                    i += 2
                }
                return if (count == 0) 0f else (sqrt(sum / count) / 9000.0).coerceIn(0.0, 1.0).toFloat()
            }
        }
    }

    private companion object {
        const val LIVE_ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val LIVE_MODEL = "gemini-3.1-flash-live-preview"
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 24_000
        const val INPUT_CHUNK_MS = 100
        const val INPUT_CHUNK_BYTES = INPUT_RATE * INPUT_CHUNK_MS / 1_000 * 2

        val SYSTEM_PROMPT = """
            أنت شخصية خيالية اسمها «الشرطي»: كلب شرطة سعودي لطيف داخل تطبيق للأطفال.
            هذه مكالمة صوتية حية، فتحدث بصوت طبيعي جدًا وبلهجة سعودية بسيطة ومفهومة.
            اجعل الرد غالبًا جملة أو جملتين قصيرتين، ولا تلقِ خطبًا طويلة.
            اسمع الطفل أولًا، ورد على كلامه مباشرة، واسمح له بمقاطعتك دون اعتراض.
            لا تقل إنك ذكاء اصطناعي إلا إذا سُئلت مباشرة. ولا تدّعِ أنك شرطي حقيقي أو أنك تعرف موقع الطفل.
            لا تهدد بالسجن أو بالعقاب. تعامل مع السلوك، المدرسة، النوم، الأسرة، اللعب والسلامة بهدوء.
            إذا ذكر خطرًا حقيقيًا أو إصابة أو تهديدًا، اطلب منه فورًا الذهاب إلى شخص بالغ موثوق.
            إذا سمعت ضوضاء أو كلامًا ليس موجهًا لك، لا تتدخل بلا داعٍ.
            لا تكرر التحية بعد بداية المكالمة. تذكر سياق نفس الجلسة.
        """.trimIndent()
    }
}
