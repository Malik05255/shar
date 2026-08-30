package com.malik.alshurti.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Local Arabic STT built on raw AudioRecord through Vosk's SpeechService.
 *
 * There is no Android SpeechRecognizer / RecognizerIntent here, therefore no
 * platform recognition UI and no OEM/Google start/stop recognition chime.
 */
class LocalArabicRecognizer(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPreparing(percent: Int, message: String)
        fun onReady()
        fun onListening()
        fun onSpeechStarted()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String, recoverable: Boolean)
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-local-stt")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager = ArabicVoskModelManager(appContext)

    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var speechService: SpeechService? = null
    @Volatile private var preparing = false
    @Volatile private var allowDownload = true
    @Volatile private var pendingListen = false
    @Volatile private var listening = false
    @Volatile private var speechStarted = false
    @Volatile private var deliveredFinal = false

    fun prepare(allowDownload: Boolean) {
        this.allowDownload = allowDownload
        if (model != null) {
            mainHandler.post(callbacks::onReady)
            return
        }
        if (preparing) return
        preparing = true
        executor.execute {
            try {
                val path = manager.ensureInstalled(allowDownload) { percent, message ->
                    mainHandler.post { callbacks.onPreparing(percent, message) }
                }
                val loaded = Model(path.absolutePath)
                model?.close()
                model = loaded
                preparing = false
                mainHandler.post {
                    callbacks.onReady()
                    if (pendingListen) {
                        pendingListen = false
                        startListening()
                    }
                }
            } catch (t: Throwable) {
                preparing = false
                pendingListen = false
                mainHandler.post {
                    callbacks.onError(
                        t.message ?: "تعذر تجهيز التعرف المحلي على الكلام.",
                        false
                    )
                }
            }
        }
    }

    fun startListening() {
        if (listening) return
        val activeModel = model
        if (activeModel == null) {
            pendingListen = true
            prepare(allowDownload)
            return
        }

        try {
            stopInternal(releaseService = true)
            val activeRecognizer = Recognizer(activeModel, SAMPLE_RATE)
            val service = SpeechService(activeRecognizer, SAMPLE_RATE)
            recognizer = activeRecognizer
            speechService = service
            speechStarted = false
            deliveredFinal = false
            listening = true
            callbacks.onListening()
            service.startListening(recognitionListener)
        } catch (t: Throwable) {
            listening = false
            callbacks.onError(t.message ?: "تعذر تشغيل الميكروفون المحلي.", true)
        }
    }

    fun stop() {
        pendingListen = false
        stopInternal(releaseService = true)
    }

    fun release() {
        pendingListen = false
        stopInternal(releaseService = true)
        executor.shutdownNow()
        runCatching { model?.close() }
        model = null
    }

    private fun finishUtterance(text: String) {
        if (deliveredFinal) return
        val clean = text.trim()
        if (clean.isBlank()) return
        deliveredFinal = true
        listening = false
        // Stop raw recording immediately before the assistant starts thinking/speaking.
        stopInternal(releaseService = true)
        callbacks.onFinal(clean)
    }

    private fun stopInternal(releaseService: Boolean) {
        listening = false
        val service = speechService
        speechService = null
        if (service != null) {
            runCatching { service.cancel() }
            if (releaseService) runCatching { service.shutdown() }
        }
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = jsonValue(hypothesis, "partial")
            if (text.isBlank()) return
            if (!speechStarted) {
                speechStarted = true
                callbacks.onSpeechStarted()
            }
            callbacks.onPartial(text)
        }

        override fun onResult(hypothesis: String?) {
            finishUtterance(jsonValue(hypothesis, "text"))
        }

        override fun onFinalResult(hypothesis: String?) {
            finishUtterance(jsonValue(hypothesis, "text"))
        }

        override fun onError(exception: Exception?) {
            if (deliveredFinal) return
            listening = false
            callbacks.onError(exception?.message ?: "تعذر فهم الصوت.", true)
        }

        override fun onTimeout() {
            if (deliveredFinal) return
            listening = false
            callbacks.onError("أنا سامعك، تكلم متى ما كنت جاهز.", true)
        }
    }

    private fun jsonValue(json: String?, key: String): String = runCatching {
        JSONObject(json.orEmpty()).optString(key).trim()
    }.getOrDefault("")

    private companion object {
        const val SAMPLE_RATE = 16_000.0f
    }
}

private class ArabicVoskModelManager(private val context: Context) {
    private val baseDir = File(context.filesDir, "speech-models")
    private val modelDir = File(baseDir, MODEL_DIR_NAME)

    fun ensureInstalled(
        allowDownload: Boolean,
        onProgress: (Int, String) -> Unit
    ): File {
        if (isValid(modelDir)) return modelDir
        if (!allowDownload) {
            error("الاستماع المحلي غير مثبت. شغّل وضع الإنترنت مرة واحدة لتنزيل نموذج العربية.")
        }

        baseDir.mkdirs()
        val archive = File(baseDir, "$MODEL_DIR_NAME.zip.part")
        val staging = File(baseDir, "$MODEL_DIR_NAME.installing")
        archive.delete()
        staging.deleteRecursively()
        staging.mkdirs()

        download(archive, onProgress)
        onProgress(100, "جاري تثبيت الاستماع العربي…")
        unzipStrippingRoot(archive, staging)
        archive.delete()

        if (!isValid(staging)) {
            staging.deleteRecursively()
            error("ملفات نموذج الاستماع العربي غير صالحة.")
        }

        modelDir.deleteRecursively()
        check(staging.renameTo(modelDir)) { "تعذر تثبيت نموذج الاستماع العربي." }
        return modelDir
    }

    private fun download(target: File, onProgress: (Int, String) -> Unit) {
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AlShorti-Android")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("تعذر تنزيل نموذج الاستماع (${connection.responseCode}).")
            }
            val total = connection.contentLengthLong
            var readTotal = 0L
            var lastPercent = -1
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        readTotal += count
                        if (total > 0) {
                            val percent = ((readTotal * 100L) / total).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent, "تنزيل الاستماع العربي")
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzipStrippingRoot(archive: File, destination: File) {
        val rootCanonical = destination.canonicalFile
        ZipInputStream(BufferedInputStream(archive.inputStream(), BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                val relative = normalized.substringAfter('/', normalized)
                if (relative.isBlank()) {
                    zip.closeEntry()
                    continue
                }
                val output = File(destination, relative).canonicalFile
                check(output.path.startsWith(rootCanonical.path + File.separator)) {
                    "مسار غير صالح داخل نموذج الاستماع."
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { fileOut -> zip.copyTo(fileOut, BUFFER_SIZE) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun isValid(dir: File): Boolean =
        File(dir, "am/final.mdl").isFile && File(dir, "conf/model.conf").isFile

    private companion object {
        const val MODEL_DIR_NAME = "vosk-model-ar-mgb2-0.4"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
        const val BUFFER_SIZE = 256 * 1024
    }
}
