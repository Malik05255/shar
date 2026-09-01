package com.malik.alshurti.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs the permissively licensed multilingual Whisper Tiny INT8 files into app-private storage.
 *
 * Production APKs bundle the exact pinned model under assets/asr/whisper-tiny-int8 so listening is
 * available on first launch without a cloud quota. A verified HTTPS download remains only as a
 * recovery path for developer builds that do not contain the bundled model. Inference is always
 * local through sherpa-onnx and microphone audio never leaves the phone.
 */
class OfflineWhisperModelManager(context: Context) {
    data class InstalledModel(
        val encoder: File,
        val decoder: File,
        val tokens: File
    )

    private data class ModelFile(
        val name: String,
        val url: String,
        val minimumBytes: Long,
        val sha256: String
    )

    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val root = File(appContext.filesDir, "asr/whisper-tiny-int8")

    fun isInstalled(): Boolean = files.all(::isValid)

    @Throws(Exception::class)
    fun ensureInstalled(
        allowDownload: Boolean,
        onProgress: (percent: Int, message: String) -> Unit
    ): InstalledModel {
        root.mkdirs()

        if (!isInstalled()) {
            installBundledMissing(onProgress)
        }

        if (!isInstalled()) {
            if (!allowDownload) {
                throw IllegalStateException(
                    "ملفات الاستماع المحلي غير موجودة في هذه النسخة."
                )
            }
            downloadMissing(onProgress)
        }

        if (!isInstalled()) {
            throw IllegalStateException("تعذر تجهيز الاستماع المحلي.")
        }

        return InstalledModel(
            encoder = File(root, ENCODER_NAME),
            decoder = File(root, DECODER_NAME),
            tokens = File(root, TOKENS_NAME)
        )
    }

    /** Copies the model shipped inside the APK. Every byte is hashed before it becomes active. */
    private fun installBundledMissing(onProgress: (Int, String) -> Unit) {
        val bundledNames = runCatching {
            assets.list(BUNDLED_ASSET_DIR)?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        if (bundledNames.isEmpty()) return

        val totalWeight = files.sumOf { it.minimumBytes }
        var completedWeight = files.filter(::isValid).sumOf { it.minimumBytes }

        files.forEachIndexed { index, spec ->
            if (isValid(spec)) return@forEachIndexed
            if (spec.name !in bundledNames) return@forEachIndexed

            val destination = File(root, spec.name)
            destination.parentFile?.mkdirs()
            val partial = File(destination.absolutePath + ".asset.part")
            partial.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            var current = 0L
            assets.open("$BUNDLED_ASSET_DIR/${spec.name}").buffered(256 * 1024).use { input ->
                FileOutputStream(partial).buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        current += count
                        val fileFraction = (current.toDouble() / spec.minimumBytes.toDouble())
                            .coerceIn(0.0, 1.0)
                        val estimate = completedWeight + (spec.minimumBytes * fileFraction).toLong()
                        onProgress(
                            ((estimate * 100L) / totalWeight).toInt().coerceIn(0, 99),
                            "جاري تشغيل الاستماع المحلي… ${index + 1}/${files.size}"
                        )
                    }
                    output.flush()
                }
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (partial.length() < spec.minimumBytes || !actualHash.equals(spec.sha256, ignoreCase = true)) {
                partial.delete()
                throw IllegalStateException("نموذج الاستماع المضمّن لم يطابق النسخة الآمنة.")
            }

            destination.delete()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            writeVerifiedMarker(destination, spec)
            completedWeight += spec.minimumBytes
        }

        if (isInstalled()) {
            onProgress(100, "تم تجهيز الاستماع المحلي")
        }
    }

    /** Recovery-only path for developer APKs without bundled assets. */
    private fun downloadMissing(onProgress: (Int, String) -> Unit) {
        val totalWeight = files.sumOf { it.minimumBytes }
        var completedWeight = files.filter(::isValid).sumOf { it.minimumBytes }

        files.forEachIndexed { index, spec ->
            if (isValid(spec)) return@forEachIndexed
            val destination = File(root, spec.name)
            destination.parentFile?.mkdirs()
            val partial = File(destination.absolutePath + ".part")
            partial.delete()

            val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AlShorti-Android/offline-asr")
            }
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("فشل تحميل التعرف الصوتي (${connection.responseCode}).")
                }
                val announcedLength = connection.contentLengthLong.takeIf { it > 0L }
                var current = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.buffered(256 * 1024).use { input ->
                    FileOutputStream(partial).buffered(256 * 1024).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            current += count
                            val fileFraction = announcedLength?.let {
                                (current.toDouble() / it.toDouble()).coerceIn(0.0, 1.0)
                            } ?: (current.toDouble() / spec.minimumBytes.toDouble()).coerceIn(0.0, 1.0)
                            val estimate = completedWeight + (spec.minimumBytes * fileFraction).toLong()
                            onProgress(
                                ((estimate * 100L) / totalWeight).toInt().coerceIn(0, 99),
                                "جاري تجهيز الاستماع المحلي… ${index + 1}/${files.size}"
                            )
                        }
                        output.flush()
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (partial.length() < spec.minimumBytes || !actualHash.equals(spec.sha256, ignoreCase = true)) {
                    partial.delete()
                    throw IllegalStateException("ملف التعرف الصوتي لم يطابق النسخة المثبتة الآمنة.")
                }
                destination.delete()
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                writeVerifiedMarker(destination, spec)
                completedWeight += spec.minimumBytes
            } finally {
                connection.disconnect()
                partial.takeIf { it.exists() }?.delete()
            }
        }
        if (!isInstalled()) throw IllegalStateException("تعذر إكمال ملفات التعرف الصوتي المحلي.")
        onProgress(100, "تم تجهيز الاستماع المحلي")
    }

    private fun isValid(spec: ModelFile): Boolean {
        val file = File(root, spec.name)
        if (!file.isFile || file.length() < spec.minimumBytes) return false
        val marker = File(root, spec.name + ".verified")
        val markerText = markerText(file, spec)
        if (marker.isFile && marker.readText() == markerText) return true

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val ok = actual.equals(spec.sha256, ignoreCase = true)
        if (ok) marker.writeText(markerText) else marker.delete()
        return ok
    }

    private fun writeVerifiedMarker(file: File, spec: ModelFile) {
        File(root, spec.name + ".verified").writeText(markerText(file, spec))
    }

    private fun markerText(file: File, spec: ModelFile): String =
        "${file.length()}:${file.lastModified()}:${spec.sha256}"

    private companion object {
        const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"
        const val BUNDLED_ASSET_DIR = "asr/whisper-tiny-int8"
        const val ENCODER_NAME = "tiny-encoder.int8.onnx"
        const val DECODER_NAME = "tiny-decoder.int8.onnx"
        const val TOKENS_NAME = "tiny-tokens.txt"

        val files = listOf(
            ModelFile(
                ENCODER_NAME,
                "$BASE/$ENCODER_NAME?download=true",
                12_000_000L,
                "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"
            ),
            ModelFile(
                DECODER_NAME,
                "$BASE/$DECODER_NAME?download=true",
                85_000_000L,
                "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"
            ),
            ModelFile(
                TOKENS_NAME,
                "$BASE/$TOKENS_NAME?download=true",
                700_000L,
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
            )
        )
    }
}
