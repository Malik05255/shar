package com.malik.alshurti

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Versioned delivery for cinematic 3D assets outside the APK.
 *
 * A content pack is promoted atomically only after every declared GLB matches its SHA-256. Existing
 * validated packs remain usable offline. This lets character/office art evolve independently from
 * app-code updates while keeping exact bytes and avoiding package growth.
 */
object Runtime3DContentPackManager {
    sealed interface State {
        data object Unknown : State
        data object Checking : State
        data class Ready(val pack: Pack) : State
        data object Unavailable : State
    }

    data class ActorFile(
        val actor: SceneActorId,
        val file: File,
        val sha256: String
    )

    data class Pack(
        val version: String,
        val actors: Map<SceneActorId, ActorFile>
    ) {
        fun uriFor(actor: SceneActorId): String? = actors[actor]?.file?.let { "file://${it.absolutePath}" }
        fun hasRequiredWorld(): Boolean = Runtime3DAssetCatalog.actors
            .filter { it.persistent }
            .all { it.id in actors }
    }

    private data class RemoteActor(
        val actor: SceneActorId,
        val url: String,
        val sha256: String,
        val bytes: Long
    )

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun ensureReady(context: Context): Pack? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val current = (_state.value as? State.Ready)?.pack
        if (current?.hasRequiredWorld() == true) return@withContext current

        _state.value = State.Checking
        val local = findValidatedLocalPack(appContext)
        if (local?.hasRequiredWorld() == true) {
            _state.value = State.Ready(local)
            return@withContext local
        }

        val downloaded = runCatching { downloadCurrentPack(appContext) }.getOrNull()
        if (downloaded?.hasRequiredWorld() == true) {
            _state.value = State.Ready(downloaded)
            downloaded
        } else {
            _state.value = State.Unavailable
            null
        }
    }

    private fun findValidatedLocalPack(context: Context): Pack? {
        val root = File(context.filesDir, ROOT_DIR)
        val active = File(root, ACTIVE_FILE)
        val version = active.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (version.isBlank()) return bundledPack(context)

        val packDir = File(root, version)
        val manifest = File(packDir, LOCAL_MANIFEST)
        if (!manifest.isFile) return bundledPack(context)
        return runCatching { parseLocalPack(packDir, manifest.readText()) }.getOrNull() ?: bundledPack(context)
    }

    private fun bundledPack(context: Context): Pack? {
        val names = context.assets.list("models").orEmpty().toSet()
        val required = Runtime3DAssetCatalog.actors.filter { it.persistent }
        if (!required.all { it.glbPath.substringAfterLast('/') in names }) return null
        // Bundled assets are represented through asset:// locations by RealPoliceDogStage directly;
        // downloaded Pack only tracks file:// assets. A null here preserves the existing asset path.
        return null
    }

    private fun downloadCurrentPack(context: Context): Pack? {
        val manifestText = downloadText(MANIFEST_URL) ?: return null
        val json = JSONObject(manifestText)
        if (!json.optBoolean("enabled", false)) return null
        if (json.optInt("schema", 0) != MANIFEST_SCHEMA) return null

        val version = json.optString("packVersion").trim()
        if (version.isBlank()) return null
        val actorsJson = json.optJSONArray("actors") ?: return null
        val remoteActors = buildList {
            for (index in 0 until actorsJson.length()) {
                val item = actorsJson.optJSONObject(index) ?: continue
                val actor = runCatching { SceneActorId.valueOf(item.getString("id")) }.getOrNull() ?: continue
                val url = item.optString("url").trim()
                val hash = item.optString("sha256").trim().lowercase()
                val bytes = item.optLong("bytes", 0L)
                if (!url.startsWith("https://") || !hash.matches(Regex("[0-9a-f]{64}")) || bytes <= 0L) continue
                add(RemoteActor(actor, url, hash, bytes))
            }
        }

        val requiredIds = Runtime3DAssetCatalog.actors.filter { it.persistent }.map { it.id }.toSet()
        if (!remoteActors.map { it.actor }.toSet().containsAll(requiredIds)) return null

        val root = File(context.filesDir, ROOT_DIR).apply { mkdirs() }
        val staging = File(root, ".$version.staging").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            remoteActors.forEach { actor ->
                val file = File(staging, "${actor.actor.name.lowercase()}.glb")
                downloadBinary(actor.url, file, actor.bytes)
                require(file.length() == actor.bytes) { "3D asset size mismatch: ${actor.actor}" }
                require(sha256(file) == actor.sha256) { "3D asset hash mismatch: ${actor.actor}" }
            }
            File(staging, LOCAL_MANIFEST).writeText(manifestText)

            val finalDir = File(root, version)
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.copyRecursively(finalDir, overwrite = true)
                staging.deleteRecursively()
            }
            File(root, ACTIVE_FILE).writeText(version)
            pruneOldPacks(root, keep = version)
            return parseLocalPack(finalDir, manifestText)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun parseLocalPack(packDir: File, rawManifest: String): Pack? {
        val json = JSONObject(rawManifest)
        val version = json.optString("packVersion").trim()
        val actorsJson = json.optJSONArray("actors") ?: return null
        val actors = mutableMapOf<SceneActorId, ActorFile>()
        for (index in 0 until actorsJson.length()) {
            val item = actorsJson.optJSONObject(index) ?: continue
            val actor = runCatching { SceneActorId.valueOf(item.optString("id")) }.getOrNull() ?: continue
            val expectedHash = item.optString("sha256").lowercase()
            val file = File(packDir, "${actor.name.lowercase()}.glb")
            if (file.isFile && expectedHash.matches(Regex("[0-9a-f]{64}")) && sha256(file) == expectedHash) {
                actors[actor] = ActorFile(actor, file, expectedHash)
            }
        }
        return Pack(version, actors)
    }

    private fun downloadText(url: String): String? {
        val connection = open(url)
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadBinary(url: String, target: File, expectedBytes: Long) {
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        val connection = open(url)
        try {
            require(connection.responseCode in 200..299) { "3D asset download failed." }
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        require(copied <= expectedBytes) { "3D asset exceeded declared size." }
                    }
                    output.fd.sync()
                }
            }
            require(copied == expectedBytes) { "3D asset is incomplete." }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AlShorti-3D/${BuildConfig.VERSION_NAME}")
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pruneOldPacks(root: File, keep: String) {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name != keep && !it.name.startsWith('.') }
            .forEach { runCatching { it.deleteRecursively() } }
    }

    private const val MANIFEST_SCHEMA = 1
    private const val ROOT_DIR = "runtime3d"
    private const val ACTIVE_FILE = "active.txt"
    private const val LOCAL_MANIFEST = "manifest.json"
    private const val BUFFER_SIZE = 256 * 1024
    private const val NETWORK_TIMEOUT_MS = 30_000
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/Malik05255/shar/main/runtime3d/manifest.json"
}
