package com.malik.alshurti

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.roundToInt

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState

    data class DeltaPackage(
        val patchUrl: String,
        val manifestUrl: String,
        val patchSizeBytes: Long
    )

    data class Available(
        val versionName: String,
        val apkUrl: String,
        val sha256Url: String,
        val releaseNotes: String,
        val delta: DeltaPackage? = null
    ) : AppUpdateState

    data class Downloading(
        val versionName: String,
        val progressPercent: Int,
        val delta: Boolean
    ) : AppUpdateState

    data class PermissionRequired(val versionName: String) : AppUpdateState
    data class Installing(val versionName: String) : AppUpdateState
    data class Error(val message: String, val retryable: Boolean) : AppUpdateState
}

/**
 * Mandatory secure updater for sideloaded Al-Shorti builds.
 *
 * A window is exposed only when GitHub reports a strictly newer stable release. For the normal
 * previous-version -> current-version path the network downloads a BSDIFF40 delta only. The app
 * reconstructs the exact signed target APK locally from ApplicationInfo.sourceDir, verifies SHA-256
 * and signing identity, then hands it to Android's package installer. Full APK download is retained
 * solely as a compatibility fallback when a delta cannot apply (skipped versions, split APK, or a
 * non-identical source build).
 */
class AppUpdateManager(private val activity: ComponentActivity) {
    private data class PendingInstall(val versionName: String, val apkFile: File)

    private data class DeltaManifest(
        val fromVersion: String,
        val toVersion: String,
        val sourceSha256: String,
        val targetSha256: String,
        val patchSha256: String,
        val targetSizeBytes: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    @Volatile private var availableRelease: AppUpdateState.Available? = null
    @Volatile private var pendingInstall: PendingInstall? = null

    fun checkForUpdates() {
        if (_state.value is AppUpdateState.Downloading || _state.value is AppUpdateState.Installing) return
        scope.launch {
            _state.value = AppUpdateState.Checking
            runCatching { fetchLatestRelease() }
                .onSuccess { release ->
                    availableRelease = release
                    _state.value = release ?: AppUpdateState.Idle
                }
                .onFailure {
                    // No update UI is ever shown for a failed background check.
                    _state.value = AppUpdateState.Idle
                }
        }
    }

    fun startUpdate() {
        val release = availableRelease ?: (_state.value as? AppUpdateState.Available) ?: return
        scope.launch {
            runCatching { downloadAndVerify(release) }
                .onSuccess { file ->
                    pendingInstall = PendingInstall(release.versionName, file)
                    requestInstall(file, release.versionName)
                }
                .onFailure { error ->
                    _state.value = AppUpdateState.Error(
                        message = error.message ?: "تعذر تحميل التحديث.",
                        retryable = !isSigningMismatch(error)
                    )
                }
        }
    }

    fun retry() {
        when (_state.value) {
            is AppUpdateState.Error -> {
                if (availableRelease != null) startUpdate() else checkForUpdates()
            }
            is AppUpdateState.PermissionRequired -> openUnknownSourcesSettings()
            else -> Unit
        }
    }

    /** Mandatory updates cannot be dismissed after a newer version has been confirmed. */
    fun dismissForThisSession() = Unit

    fun onActivityResumed() {
        val pending = pendingInstall
        if (pending != null && canInstallPackages()) {
            launchPackageInstaller(pending.apkFile, pending.versionName)
            return
        }
        if (pending == null && _state.value is AppUpdateState.Installing) checkForUpdates()
    }

    fun release() {
        scope.cancel()
    }

    private fun fetchLatestRelease(): AppUpdateState.Available? {
        val connection = openConnection(LATEST_RELEASE_URL)
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            connection.disconnect()
            return null
        }
        if (code !in 200..299) {
            connection.disconnect()
            error("تعذر فحص التحديثات الآن.")
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val json = JSONObject(body)
        if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return null

        val tag = json.optString("tag_name").removePrefix("v").trim()
        if (tag.isBlank() || compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) return null

        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var shaUrl: String? = null
        var deltaUrl: String? = null
        var deltaManifestUrl: String? = null
        var deltaSize = 0L

        val deltaBase = "AlShorti-${BuildConfig.VERSION_NAME}-to-${tag}"
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) continue

            when {
                name.equals("AlShorti-${tag}.apk", ignoreCase = true) -> apkUrl = url
                name.equals("AlShorti-${tag}.apk.sha256", ignoreCase = true) -> shaUrl = url
                name.equals("${deltaBase}.bsdiff", ignoreCase = true) -> {
                    deltaUrl = url
                    deltaSize = asset.optLong("size", 0L)
                }
                name.equals("${deltaBase}.delta.json", ignoreCase = true) -> deltaManifestUrl = url
            }
        }
        if (apkUrl == null || shaUrl == null) return null

        val delta = if (deltaUrl != null && deltaManifestUrl != null) {
            AppUpdateState.DeltaPackage(
                patchUrl = deltaUrl,
                manifestUrl = deltaManifestUrl,
                patchSizeBytes = deltaSize
            )
        } else null

        return AppUpdateState.Available(
            versionName = tag,
            apkUrl = apkUrl,
            sha256Url = shaUrl,
            releaseNotes = json.optString("body").take(UPDATE_NOTES_LIMIT),
            delta = delta
        )
    }

    private fun downloadAndVerify(release: AppUpdateState.Available): File {
        val updateDir = File(activity.externalCacheDir ?: activity.cacheDir, "updates").apply { mkdirs() }
        updateDir.listFiles()?.forEach { runCatching { it.delete() } }

        val releaseHash = expectedHash(release.sha256Url)
        val delta = release.delta
        if (delta != null && activity.applicationInfo.splitSourceDirs.isNullOrEmpty()) {
            val patched = runCatching {
                downloadApplyAndVerifyDelta(release, delta, updateDir, releaseHash)
            }.getOrNull()
            if (patched != null) return patched
        }

        return downloadFullAndVerify(release, updateDir, releaseHash)
    }

    private fun downloadApplyAndVerifyDelta(
        release: AppUpdateState.Available,
        delta: AppUpdateState.DeltaPackage,
        updateDir: File,
        releaseHash: String
    ): File {
        val manifest = parseDeltaManifest(downloadText(delta.manifestUrl))
        require(manifest.fromVersion == BuildConfig.VERSION_NAME) { "Delta source version mismatch." }
        require(manifest.toVersion == release.versionName) { "Delta target version mismatch." }
        require(manifest.targetSha256 == releaseHash) { "Delta target hash does not match release APK." }

        val installedApk = File(activity.applicationInfo.sourceDir)
        require(installedApk.isFile && installedApk.length() > 0L) { "Installed APK is unavailable for delta update." }
        require(sha256(installedApk) == manifest.sourceSha256) {
            "Installed APK differs from the delta source."
        }

        val patchFile = File(updateDir, "AlShorti-${manifest.fromVersion}-to-${manifest.toVersion}.bsdiff")
        downloadFile(
            url = delta.patchUrl,
            target = patchFile,
            versionName = release.versionName,
            delta = true,
            advertisedBytes = delta.patchSizeBytes
        )
        require(sha256(patchFile) == manifest.patchSha256) { "Delta patch integrity check failed." }

        val target = File(updateDir, "AlShorti-${release.versionName}.apk")
        val partial = File(updateDir, "${target.name}.rebuild")
        runCatching { partial.delete() }
        BsDiffPatch.apply(installedApk, patchFile, partial)

        require(partial.length() == manifest.targetSizeBytes) { "Reconstructed APK size mismatch." }
        require(sha256(partial) == releaseHash) { "Reconstructed APK integrity check failed." }
        finalizeDownloadedApk(partial, target, release.versionName)
        patchFile.delete()
        return target
    }

    private fun downloadFullAndVerify(
        release: AppUpdateState.Available,
        updateDir: File,
        releaseHash: String
    ): File {
        val target = File(updateDir, "AlShorti-${release.versionName}.apk")
        downloadFile(
            url = release.apkUrl,
            target = target,
            versionName = release.versionName,
            delta = false,
            advertisedBytes = 0L
        )
        require(sha256(target) == releaseHash) {
            target.delete()
            "Full update integrity check failed."
        }
        verifyCandidate(target, release.versionName)
        return target
    }

    private fun finalizeDownloadedApk(partial: File, target: File, versionName: String) {
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        verifyCandidate(target, versionName)
    }

    private fun verifyCandidate(apkFile: File, expectedVersionName: String) {
        val candidate = activity.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: run {
            apkFile.delete()
            error("Reconstructed update is not a valid Android package.")
        }

        if (candidate.packageName != activity.packageName || candidate.versionName != expectedVersionName) {
            apkFile.delete()
            error("Update package identity/version mismatch.")
        }
        if (!hasSameSigningCertificate(apkFile)) {
            apkFile.delete()
            throw SigningMismatchException(
                "يلزم أن تكون النسخة الحالية والتحديث موقّعين بنفس المفتاح الدائم حتى يثبت التحديث فوق النسخة السابقة."
            )
        }
    }

    private fun downloadFile(
        url: String,
        target: File,
        versionName: String,
        delta: Boolean,
        advertisedBytes: Long
    ) {
        val partial = File(target.parentFile, "${target.name}.part")
        runCatching { partial.delete() }
        val connection = openConnection(url)
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            error("تعذر تنزيل ملف التحديث.")
        }

        val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: advertisedBytes
        var copied = 0L
        var lastProgress = -1
        connection.inputStream.use { input ->
            FileOutputStream(partial).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val progress = if (totalBytes > 0L) {
                        ((copied.toDouble() / totalBytes.toDouble()) * 100.0)
                            .roundToInt().coerceIn(0, 100)
                    } else 0
                    if (progress != lastProgress) {
                        lastProgress = progress
                        _state.value = AppUpdateState.Downloading(versionName, progress, delta)
                    }
                }
                output.fd.sync()
            }
        }
        connection.disconnect()

        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    private fun parseDeltaManifest(raw: String): DeltaManifest {
        val json = JSONObject(raw)
        require(json.optString("format") == "BSDIFF40") { "Unsupported delta manifest." }
        fun hash(name: String): String = json.optString(name).trim().lowercase().also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid delta manifest hash: $name" }
        }
        return DeltaManifest(
            fromVersion = json.getString("fromVersion"),
            toVersion = json.getString("toVersion"),
            sourceSha256 = hash("sourceSha256"),
            targetSha256 = hash("targetSha256"),
            patchSha256 = hash("patchSha256"),
            targetSizeBytes = json.getLong("targetSizeBytes")
        )
    }

    private fun expectedHash(url: String): String = downloadText(url)
        .trim()
        .substringBefore(' ')
        .lowercase()
        .also { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid update SHA-256." } }

    private fun requestInstall(apkFile: File, versionName: String) {
        if (canInstallPackages()) {
            launchPackageInstaller(apkFile, versionName)
        } else {
            _state.value = AppUpdateState.PermissionRequired(versionName)
            openUnknownSourcesSettings()
        }
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        activity.runOnUiThread {
            runCatching { activity.startActivity(intent) }.onFailure {
                _state.value = AppUpdateState.Error(
                    "افتح إعدادات الجهاز واسمح لتطبيق الشرطي بتثبيت التحديثات.",
                    retryable = true
                )
            }
        }
    }

    private fun launchPackageInstaller(apkFile: File, versionName: String) {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            pendingInstall = null
            _state.value = AppUpdateState.Error("ملف التحديث غير موجود. أعد المحاولة.", true)
            return
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        _state.value = AppUpdateState.Installing(versionName)
        activity.runOnUiThread {
            runCatching { activity.startActivity(intent) }
                .onSuccess { pendingInstall = null }
                .onFailure {
                    _state.value = AppUpdateState.Error("تعذر فتح مثبت Android. أعد المحاولة.", true)
                }
        }
    }

    private fun hasSameSigningCertificate(apkFile: File): Boolean {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = activity.packageManager.getPackageInfo(activity.packageName, flags)
        val candidate = activity.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false

        val installedDigests = installed.signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        val candidateDigests = candidate.signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        return installedDigests.isNotEmpty() && installedDigests == candidateDigests
    }

    private fun downloadText(url: String): String {
        val connection = openConnection(url)
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            error("تعذر التحقق من ملف التحديث.")
        }
        val value = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        return value
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AlShorti/${BuildConfig.VERSION_NAME}")
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun compareVersions(a: String, b: String): Int {
        val left = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(left.size, right.size)
        repeat(size) { index ->
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun isSigningMismatch(error: Throwable): Boolean = error is SigningMismatchException
    private class SigningMismatchException(message: String) : IllegalStateException(message)

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/Malik05255/shar/releases/latest"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val NETWORK_TIMEOUT_MS = 30_000
        const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
        const val UPDATE_NOTES_LIMIT = 1_600
    }
}
