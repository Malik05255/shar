package com.malik.alshurti.neural

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the large neural voice weights outside the APK.
 *
 * Online mode may download them once. After that the exact same model is used in
 * offline mode from app-private storage, with no API key and no per-minute quota.
 */
class SupertonicModelManager(context: Context) {
    data class InstalledModel(
        val onnxDir: File,
        val voiceStyle: File
    )

    private data class ModelFile(
        val relativePath: String,
        val url: String,
        val minimumBytes: Long
    )

    private val root = File(context.filesDir, "neural/supertonic3")

    fun isInstalled(): Boolean = files.all { spec ->
        val file = File(root, spec.relativePath)
        file.isFile && file.length() >= spec.minimumBytes
    }

    @Throws(Exception::class)
    fun ensureInstalled(
        allowDownload: Boolean,
        onProgress: (percent: Int, message: String) -> Unit
    ): InstalledModel {
        root.mkdirs()
        if (!isInstalled()) {
            if (!allowDownload) {
                throw IllegalStateException(
                    "الصوت العصبي غير محمّل بعد. اختر وضع الإنترنت مرة واحدة لتحميل صوت الشرطي، وبعدها يعمل بدون إنترنت."
                )
            }
            downloadMissing(onProgress)
        }
        return InstalledModel(
            onnxDir = File(root, "onnx"),
            voiceStyle = File(root, "voice_styles/M1.json")
        )
    }

    private fun downloadMissing(onProgress: (Int, String) -> Unit) {
        val totalWeight = files.sumOf { it.minimumBytes.coerceAtLeast(1L) }
        var completedWeight = files.sumOf { spec ->
            val file = File(root, spec.relativePath)
            if (file.isFile && file.length() >= spec.minimumBytes) spec.minimumBytes else 0L
        }

        files.forEachIndexed { index, spec ->
            val destination = File(root, spec.relativePath)
            if (destination.isFile && destination.length() >= spec.minimumBytes) return@forEachIndexed
            destination.parentFile?.mkdirs()
            val partial = File(destination.absolutePath + ".part")
            if (partial.exists()) partial.delete()

            onProgress(
                ((completedWeight * 100L) / totalWeight).toInt().coerceIn(0, 99),
                "جاري تحميل الصوت الطبيعي لأول مرة… ${index + 1}/${files.size}"
            )

            val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AlShorti-Android/0.2")
            }

            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("فشل تحميل الصوت (${connection.responseCode}).")
                }

                val contentLength = connection.contentLengthLong.takeIf { it > 0L }
                var current = 0L
                connection.inputStream.buffered(256 * 1024).use { input ->
                    FileOutputStream(partial).buffered(256 * 1024).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            current += count
                            val fileFraction = contentLength?.let {
                                (current.toDouble() / it.toDouble()).coerceIn(0.0, 1.0)
                            } ?: 0.0
                            val estimate = completedWeight + (spec.minimumBytes * fileFraction).toLong()
                            onProgress(
                                ((estimate * 100L) / totalWeight).toInt().coerceIn(0, 99),
                                "جاري تحميل الصوت الطبيعي لأول مرة… ${index + 1}/${files.size}"
                            )
                        }
                        output.flush()
                    }
                }

                if (partial.length() < spec.minimumBytes) {
                    partial.delete()
                    throw IllegalStateException("ملف الصوت وصل ناقصًا. أعد المحاولة على اتصال ثابت.")
                }
                if (destination.exists()) destination.delete()
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                completedWeight += spec.minimumBytes
            } finally {
                connection.disconnect()
            }
        }
        onProgress(100, "تم تجهيز الصوت العصبي العربي")
    }

    companion object {
        private const val BASE = "https://huggingface.co/Supertone/supertonic-3/resolve/main"

        private val files = listOf(
            ModelFile("onnx/duration_predictor.onnx", "$BASE/onnx/duration_predictor.onnx?download=true", 3_000_000L),
            ModelFile("onnx/text_encoder.onnx", "$BASE/onnx/text_encoder.onnx?download=true", 30_000_000L),
            ModelFile("onnx/vector_estimator.onnx", "$BASE/onnx/vector_estimator.onnx?download=true", 200_000_000L),
            ModelFile("onnx/vocoder.onnx", "$BASE/onnx/vocoder.onnx?download=true", 80_000_000L),
            ModelFile("onnx/tts.json", "$BASE/onnx/tts.json?download=true", 4_000L),
            ModelFile("onnx/unicode_indexer.json", "$BASE/onnx/unicode_indexer.json?download=true", 150_000L),
            ModelFile("voice_styles/M1.json", "$BASE/voice_styles/M1.json?download=true", 200_000L)
        )
    }
}
