package com.malik.alshurti

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Bounded exact-byte cache for cinematic MP4 delivery.
 *
 * Files are copied byte-for-byte from CDN. There is no video decoding, resizing, transcoding or
 * quality selection in this layer. Streaming can start immediately from the remote URL while likely
 * next clips are prefetched in the background.
 */
object CinematicMediaCache {
    private const val DIRECTORY = "cinematic-v1"
    private const val MAX_BYTES = 192L * 1024L * 1024L
    private const val MAX_FILES = 16
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 45_000

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alshorti-cinematic-cache").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    fun localOrRemote(context: Context, url: String): String {
        val file = fileFor(context, url)
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file.toURI().toString()
        }
        return url
    }

    fun prefetch(context: Context, urls: Collection<String>) {
        if (urls.isEmpty()) return
        val appContext = context.applicationContext
        urls.distinct().forEach { url ->
            executor.execute { ensureCached(appContext, url) }
        }
    }

    private fun ensureCached(context: Context, url: String) {
        val target = fileFor(context, url)
        if (target.isFile && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            return
        }

        val directory = target.parentFile ?: return
        directory.mkdirs()
        val partial = File(directory, target.name + ".part")

        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "video/mp4,*/*")
            }
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) return@runCatching
                partial.outputStream().buffered().use { output ->
                    connection.inputStream.buffered().use { input -> input.copyTo(output) }
                }
                if (partial.length() <= 0L) return@runCatching
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                target.setLastModified(System.currentTimeMillis())
                trim(context)
            } finally {
                connection.disconnect()
            }
        }.onFailure {
            partial.delete()
        }
    }

    private fun trim(context: Context) {
        val directory = directory(context)
        val files = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "mp4" }
            .sortedBy { it.lastModified() }
            .toMutableList()

        var total = files.sumOf { it.length() }
        while (files.size > MAX_FILES || total > MAX_BYTES) {
            val oldest = files.removeFirstOrNull() ?: break
            val bytes = oldest.length()
            if (oldest.delete()) total -= bytes
        }
    }

    private fun fileFor(context: Context, url: String): File =
        File(directory(context), sha256(url) + ".mp4")

    private fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
