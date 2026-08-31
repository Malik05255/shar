package com.malik.alshurti

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * A validated local pack is exposed immediately so the office never waits on the network. The
 * remote manifest is then checked in the background of this suspend call. If it points to a newer
 * pack, unchanged actors are copied from the active pack and only changed GLBs are downloaded.
 * Promotion is atomic and only happens after every declared actor matches size + SHA-256.
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
    private val updateMutex = Mutex()

    /**
     * Returns the best usable pack. A valid local pack remains active if the network, manifest, or
     * replacement pack fails; a failed remote refresh can never evict a working office.
     */
    suspend fun ensureReady(context: Context): Pack? = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            val appContext = context.applicationContext
            val memory = (_state.value as? State.Ready)?.pack
            val local = memory?.takeIf { it.hasRequiredWorld() }
                ?: findValidatedLocalPack(appContext)?.takeIf { it.hasRequiredWorld() }

            if (local != null) {
                _state.value = State.Ready(local)
            } else {
                _state.value = State.Checking
            }

            val refreshed = runCatching {
                downloadCurrentPack(appContext, previous = local)
            }.getOrNull()

            when {
                refreshed?.hasRequiredWorld() == true -> {
                    _state.value = State.Ready(refreshed)
                    refreshed
                }
                local != null -> {
                    _state.value = State.Ready(local)
                    local
                }
                else -> {
                    _state.value = State.Unavailable
                    null
                }
            }
        }
    }

    private fun findValidatedLocalPack(context: Context): Pack? {
        val root = File(context.filesDir, ROOT_DIR)
        val active = File(root, ACTIVE_FILE)
        val version = active.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (version.isBlank()) return null

        val packDir = File(root, version)
        val manifest = File(packDir, LOCAL_MANIFEST)
        if (!manifest.isFile) return null
        return runCatching { parseLocalPack(packDir, manifest.readText()) }.getOrNull()
    }

    private fun downloadCurrentPack(context: Context, previous: Pack?): Pack? {
        val manifestText = downloadText(MANIFEST_URL) ?: return previous
        val json = JSONObject(manifestText)
        if (!json.optBoolean("enabled", false)) return previous
        if (json.optInt("schema", 0) != MANIFEST_SCHEMA) return previous

        val minimumAppVersion = json.optString("minimumAppVersion").trim()
        if (minimumAppVersion.isNotBlank() &&
            compareVersionNames(BuildConfig.VERSION_NAME, minimumAppVersion) < 0
        ) {
            return previous
        }

        val version = json.optString("packVersion").trim()
        if (version.isBlank()) return previous
        if (previous?.version == version && previous.hasRequiredWorld()) return previous

        val actorsJson = json.optJSONArray("actors") ?: return previous
        val remoteActors = buildList {
            for (index in 0 until actorsJson.length()) {
                val item = actorsJson.optJSONObject(index) ?: continue
                val actor = runCatching { SceneActorId.valueOf(item.getString("id")) }.getOrNull() ?: continue
                val url = item.optString("url").trim()
                val hash = item.optString("sha256").trim().lowercase()
                val bytes = item.optLong("bytes", 0L)
                if (!url.startsWith("https://") || !hash.matches(SHA_256_REGEX) || bytes <= 0L) continue
                add(RemoteActor(actor, url, hash, bytes))
            }
        }

        val requiredIds = Runtime3DAssetCatalog.actors.filter { it.persistent }.map { it.id }.toSet()
        if (!remoteActors.map { it.actor }.toSet().containsAll(requiredIds)) return previous

        val declaredTotal = remoteActors.sumOf { it.bytes }
        require(declaredTotal in 1..MAX_PACK_BYTES) { "3D content pack exceeds safety limit." }

        val root = File(context.filesDir, ROOT_DIR).apply { mkdirs() }
        val staging = File(root, ".$version.staging").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            remoteActors.forEach { actor ->
                val target = File(staging, "${actor.actor.name.lowercase()}.glb")
                val reusable = previous?.actors?.get(actor.actor)
                    ?.takeIf { it.sha256 == actor.sha256 && it.file.isFile && it.file.length() == actor.bytes }

                if (reusable != null) {
                    reusable.file.copyTo(target, overwrite = true)
                } else {
                    downloadBinary(actor.url, target, actor.bytes)
                }

                require(target.length() == actor.bytes) { "3D asset size mismatch: ${actor.actor}" }
                require(sha256(target) == actor.sha256) { "3D asset hash mismatch: ${actor.actor}" }
            }
            File(staging, LOCAL_MANIFEST).writeText(manifestText)

            val finalDir = File(root, version)
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.copyRecursively(finalDir, overwrite = true)
                staging.deleteRecursively()
            }

            val promoted = parseLocalPack(finalDir, manifestText)
                ?.takeIf { it.hasRequiredWorld() }
                ?: error("3D pack did not validate after promotion.")

            // active.txt is written last: a process death before this line keeps the prior pack active.
            File(root, ACTIVE_FILE).writeText(version)
            pruneOldPacks(root, keep = version)
            return promoted
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun parseLocalPack(packDir: File, rawManifest: String): Pack? {
        val json = JSONObject(rawManifest)
        if (json.optInt("schema", 0) != MANIFEST_SCHEMA) return null
        val version = json.optString("packVersion").trim()
        if (version.isBlank()) return null

        val actorsJson = json.optJSONArray("actors") ?: return null
        val actors = mutableMapOf<SceneActorId, ActorFile>()
        for (index in 0 until actorsJson.length()) {
            val item = actorsJson.optJSONObject(index) ?: continue
            val actor = runCatching { SceneActorId.valueOf(item.optString("id")) }.getOrNull() ?: continue
            val expectedHash = item.optString("sha256").lowercase()
            val expectedBytes = item.optLong("bytes", 0L)
            val file = File(packDir, "${actor.name.lowercase()}.glb")
            if (
                file.isFile &&
                expectedBytes > 0L &&
                file.length() == expectedBytes &&
                expectedHash.matches(SHA_256_REGEX) &&
                sha256(file) == expectedHash
            ) {
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
            setRequestProperty("Cache-Control", "no-cache")
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
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != keep && !it.name.startsWith('.') }
            .forEach { runCatching { it.deleteRecursively() } }
    }

    internal fun compareVersionNames(left: String, right: String): Int {
        fun parts(value: String): List<Int> = value
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        val count = maxOf(a.size, b.size)
        repeat(count) { index ->
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    private const val MANIFEST_SCHEMA = 1
    private const val ROOT_DIR = "runtime3d"
    private const val ACTIVE_FILE = "active.txt"
    private const val LOCAL_MANIFEST = "manifest.json"
    private const val BUFFER_SIZE = 256 * 1024
    private const val NETWORK_TIMEOUT_MS = 30_000
    private const val MAX_PACK_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/Malik05255/shar/main/runtime3d/manifest.json"
}
