package com.malik.alshurti.livev2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
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
 * One permanent, full-duplex Gemini Live session.
 *
 * Input: 16 kHz mono PCM16 from AudioRecord.
 * Output: 24 kHz mono PCM16 from Gemini native audio.
 * Gemini itself owns VAD, turn boundaries, context, interruption and both transcriptions.
 */
class GeminiLiveSession(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onState(state: State, detail: String = "")
        fun onUserText(text: String, interim: Boolean)
        fun onPoliceText(text: String)
        fun onInputLevel(level: Float)
        fun onOutputLevel(level: Float)
    }

    enum class State {
        IDLE,
        CONNECTING,
        LISTENING,
        USER_SPEAKING,
        POLICE_SPEAKING,
        ERROR,
        CLOSED
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val okHttp = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val active = AtomicBoolean(false)
    private val captureEpoch = AtomicLong(0L)
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var captureThread: Thread? = null
    private val player = StreamingPcmPlayer(callbacks)

    fun connect() {
        if (!active.compareAndSet(false, true)) return
        if (!microphoneAllowed()) {
            active.set(false)
            callbacks.onState(State.ERROR, "إذن الميكروفون مطلوب")
            return
        }
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            active.set(false)
            callbacks.onState(State.ERROR, "مفتاح Gemini غير موجود في هذه النسخة")
            return
        }

        configureCallAudio()
        callbacks.onState(State.CONNECTING, "فتح قناة صوتية مباشرة…")
        val request = Request.Builder()
            .url("$ENDPOINT?key=${BuildConfig.GEMINI_API_KEY}")
            .build()
        webSocket = okHttp.newWebSocket(request, socketListener)
    }

    fun disconnect() {
        if (!active.getAndSet(false)) return
        captureEpoch.incrementAndGet()
        stopMicrophone()
        webSocket?.send(
            JSONObject()
                .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
                .toString()
        )
        webSocket?.close(1000, "user ended call")
        webSocket = null
        player.flush()
        restoreAudio()
        callbacks.onState(State.CLOSED, "انتهت المكالمة")
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    fun release() {
        disconnect()
        player.release()
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!active.get()) {
                webSocket.close(1000, "inactive")
                return
            }
            if (!webSocket.send(setupMessage())) {
                fatal("تعذر إعداد Gemini Live")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!active.get()) return
            runCatching { consume(JSONObject(text)) }
                .onFailure { fatal(it.message ?: "استجابة Live غير صالحة") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (active.get()) fatal(t.message ?: "انقطع اتصال Gemini Live")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (active.getAndSet(false)) {
                stopMicrophone()
                player.flush()
                restoreAudio()
                callbacks.onState(State.CLOSED, reason)
            }
        }
    }

    private fun setupMessage(): String {
        val voice = BuildConfig.GEMINI_POLICE_VOICE.ifBlank { "Gacrux" }

        val generation = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig",
                JSONObject().put(
                    "voiceConfig",
                    JSONObject().put(
                        "prebuiltVoiceConfig",
                        JSONObject().put("voiceName", voice)
                    )
                )
            )

        val activity = JSONObject()
            .put("disabled", false)
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
            .put("prefixPaddingMs", 80)
            .put("silenceDurationMs", 420)

        val setup = JSONObject()
            .put("model", "models/$MODEL")
            .put("generationConfig", generation)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION))
                )
            )
            .put(
                "realtimeInputConfig",
                JSONObject()
                    .put("automaticActivityDetection", activity)
                    .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                    .put("turnCoverage", "TURN_INCLUDES_ONLY_ACTIVITY")
            )
            .put(
                "inputAudioTranscription",
                JSONObject().put("languageCodes", JSONArray().put("ar-SA"))
            )
            .put("outputAudioTranscription", JSONObject())

        return JSONObject().put("setup", setup).toString()
    }

    private fun consume(message: JSONObject) {
        if (message.has("setupComplete")) {
            startMicrophone()
            callbacks.onState(State.LISTENING, "تكلم… أنا أسمعك")
            sendGreetingRequest()
            return
        }

        val server = message.optJSONObject("serverContent") ?: return

        if (server.optBoolean("interrupted", false)) {
            player.flush()
            callbacks.onState(State.USER_SPEAKING, "أسمعك…")
        }

        server.optJSONObject("interimInputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                callbacks.onUserText(it, true)
                callbacks.onState(State.USER_SPEAKING, "أسمعك…")
            }

        server.optJSONObject("inputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let { callbacks.onUserText(it, false) }

        server.optJSONObject("outputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let { callbacks.onPoliceText(it) }

        val parts = server.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType", inline.optString("mime_type"))
                if (!mime.startsWith("audio/pcm")) continue
                val encoded = inline.optString("data")
                if (encoded.isBlank()) continue
                val pcm = Base64.decode(encoded, Base64.DEFAULT)
                if (pcm.isNotEmpty()) {
                    callbacks.onState(State.POLICE_SPEAKING, "الشرطي يتكلم…")
                    player.enqueue(pcm)
                }
            }
        }

        if (server.optBoolean("waitingForInput", false) || server.optBoolean("turnComplete", false)) {
            callbacks.onState(State.LISTENING, "تكلم… أنا أسمعك")
        }
    }

    private fun sendGreetingRequest() {
        val turn = JSONObject()
            .put("role", "user")
            .put(
                "parts",
                JSONArray().put(
                    JSONObject().put(
                        "text",
                        "ابدأ المكالمة بتحية سعودية قصيرة جدًا للطفل، ثم انتظر كلامه ولا تضف شرحًا."
                    )
                )
            )
        webSocket?.send(
            JSONObject()
                .put(
                    "clientContent",
                    JSONObject()
                        .put("turns", JSONArray().put(turn))
                        .put("turnComplete", true)
                )
                .toString()
        )
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophone() {
        if (!active.get() || audioRecord != null) return
        if (!microphoneAllowed()) {
            fatal("إذن الميكروفون غير ممنوح")
            return
        }

        val minimum = AudioRecord.getMinBufferSize(
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) {
            fatal("الجهاز لا يدعم التقاط PCM المطلوب")
            return
        }

        val record = createRecorder(maxOf(minimum * 2, CHUNK_BYTES * 4)) ?: run {
            fatal("تعذر فتح الميكروفون")
            return
        }
        audioRecord = record

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(record.audioSessionId) }
                .getOrNull()
                ?.also { runCatching { it.enabled = true } }
        }

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            fatal("الميكروفون لم يبدأ التسجيل")
            return
        }

        val epoch = captureEpoch.incrementAndGet()
        captureThread = Thread({ capture(record, epoch) }, "alshorti-v2-mic").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(bufferSize: Int): AudioRecord? {
        if (!microphoneAllowed()) return null
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val recorder = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(INPUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            runCatching { recorder.release() }
        }
        return null
    }

    private fun capture(record: AudioRecord, epoch: Long) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (active.get() && captureEpoch.get() == epoch) {
            val read = runCatching {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            }.getOrElse { -1 }
            if (read <= 0) continue

            val pcm = buffer.copyOf(read)
            callbacks.onInputLevel(level(pcm, 7000.0))
            val blob = JSONObject()
                .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                .put("mimeType", "audio/pcm;rate=$INPUT_RATE")
            val sent = webSocket?.send(
                JSONObject()
                    .put("realtimeInput", JSONObject().put("audio", blob))
                    .toString()
            ) ?: false
            if (!sent) {
                fatal("تعذر إرسال صوت الميكروفون")
                break
            }
        }
    }

    private fun stopMicrophone() {
        captureEpoch.incrementAndGet()
        val record = audioRecord
        audioRecord = null
        echoCanceler?.let { runCatching { it.release() } }
        echoCanceler = null
        if (record != null) {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            runCatching { record.release() }
        }
        captureThread?.interrupt()
        captureThread = null
        callbacks.onInputLevel(0f)
    }

    private fun fatal(message: String) {
        if (!active.getAndSet(false)) return
        stopMicrophone()
        player.flush()
        webSocket?.cancel()
        webSocket = null
        restoreAudio()
        callbacks.onState(State.ERROR, message)
    }

    private fun microphoneAllowed(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun configureCallAudio() {
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let(audioManager::setCommunicationDevice)
            }
        } else {
            runCatching { audioManager.isSpeakerphoneOn = true }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }

    private class StreamingPcmPlayer(private val callbacks: Callbacks) {
        private data class Packet(val epoch: Long, val data: ByteArray)

        private val epoch = AtomicLong(0L)
        private val alive = AtomicBoolean(true)
        private val queue = LinkedBlockingQueue<Packet>(96)
        private val track = createTrack()
        private val thread = Thread({ loop() }, "alshorti-v2-speaker").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }

        fun enqueue(data: ByteArray) {
            val packet = Packet(epoch.get(), data)
            if (!queue.offer(packet)) {
                queue.poll()
                queue.offer(packet)
            }
        }

        fun flush() {
            epoch.incrementAndGet()
            queue.clear()
            runCatching {
                track.pause()
                track.flush()
                track.play()
            }
            callbacks.onOutputLevel(0f)
        }

        fun release() {
            if (!alive.getAndSet(false)) return
            queue.clear()
            thread.interrupt()
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }

        private fun loop() {
            runCatching { track.play() }
            while (alive.get()) {
                val packet = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    continue
                }
                if (packet.epoch != epoch.get()) continue
                callbacks.onOutputLevel(level(packet.data, 9000.0))
                var offset = 0
                while (offset < packet.data.size && packet.epoch == epoch.get() && alive.get()) {
                    val n = track.write(
                        packet.data,
                        offset,
                        packet.data.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (n <= 0) break
                    offset += n
                }
                if (queue.isEmpty()) callbacks.onOutputLevel(0f)
            }
        }

        companion object {
            private fun createTrack(): AudioTrack {
                val minimum = AudioTrack.getMinBufferSize(
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
                    .setBufferSizeInBytes(maxOf(minimum * 4, OUTPUT_RATE * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }
        }
    }

    companion object {
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MODEL = "gemini-3.1-flash-live-preview"
        private const val INPUT_RATE = 16_000
        private const val OUTPUT_RATE = 24_000
        private const val CHUNK_MS = 100
        private const val CHUNK_BYTES = INPUT_RATE * CHUNK_MS / 1_000 * 2

        private fun level(bytes: ByteArray, divisor: Double): Float {
            if (bytes.size < 2) return 0f
            var energy = 0.0
            var samples = 0
            var i = 0
            while (i + 1 < bytes.size) {
                val low = bytes[i].toInt() and 0xff
                val high = bytes[i + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toInt()
                energy += sample.toDouble() * sample.toDouble()
                samples++
                i += 2
            }
            if (samples == 0) return 0f
            return (sqrt(energy / samples) / divisor).coerceIn(0.0, 1.0).toFloat()
        }

        private val SYSTEM_INSTRUCTION = """
            أنت شخصية خيالية اسمها «الشرطي»، كلب شرطة سعودي لطيف داخل تطبيق للأطفال.
            تحدث بعربية سعودية طبيعية، قصيرة، ودافئة. هذه مكالمة صوتية حية وليست قراءة نص.
            استمع جيدًا، اسمح للطفل بمقاطعتك، ولا تكرر التحية أو الكلام المحفوظ.
            اجعل معظم الردود جملة أو جملتين. اسأل سؤال متابعة واحد فقط عندما يفيد.
            لا تدّع أنك شرطي حقيقي أو أنك تعرف موقع الطفل، ولا تهدد بالسجن أو العقاب.
            إذا ذكر الطفل خطرًا حقيقيًا أو إصابة أو تهديدًا، اطلب منه الذهاب فورًا إلى شخص بالغ موثوق.
            إذا سمعت ضوضاء أو كلامًا ليس موجهًا إليك، يمكنك تجاهله بدل الرد دائمًا.
        """.trimIndent()
    }
}
