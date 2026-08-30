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

    data class Available(
        val versionName: String,
        val apkUrl: String,
        val sha256Url: String,
        val releaseNotes: String
    ) : AppUpdateState

    data class Downloading(
        val versionName: String,
        val progressPercent: Int
    ) : AppUpdateState

    data class PermissionRequired(
        val versionName: String
    ) : AppUpdateState

    data class Installing(
        val versionName: String
    ) : AppUpdateState

    data class Error(
        val message: String,
        val retryable: Boolean
    ) : AppUpdateState
}

/**
 * Secure self-updater for sideloaded Al-Shorti builds.
 *
 * The updater trusts only the public Malik05255/shar GitHub Releases endpoint. A release must
 * contain one APK plus a matching `.sha256` asset. Both integrity and Android signing identity are
 * verified before the package installer opens. Android still owns the final install confirmation;
 * silent installation is intentionally not attempted.
 */
class AppUpdateManager(private val activity: ComponentActivity) {
    private data class PendingInstall(
        val versionName: String,
        val apkFile: File
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    @Volatile
    private var availableRelease: AppUpdateState.Available? = null

    @Volatile
    private var pendingInstall: PendingInstall? = null

    fun checkForUpdates() {
        if (_state.value is AppUpdateState.Downloading || _state.value is AppUpdateState.Installing) return

        scope.launch {
            _state.value = AppUpdateState.Checking
            val result = runCatching { fetchLatestRelease() }
            result.onSuccess { release ->
                availableRelease = release
                _state.value = release ?: AppUpdateState.Idle
            }.onFailure {
                // Update checks must never block the actual call experience. A transient GitHub or
                // network failure is silent; the next app launch checks again automatically.
                _state.value = AppUpdateState.Idle
            }
        }
    }

    fun startUpdate() {
        val release = availableRelease ?: (_state.value as? AppUpdateState.Available) ?: return
        scope.launch {
            runCatching {
                downloadAndVerify(release)
            }.onSuccess { file ->
                pendingInstall = PendingInstall(release.versionName, file)
                requestInstall(file, release.versionName)
            }.onFailure { error ->
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
            else -> checkForUpdates()
        }
    }

    fun dismissForThisSession() {
        if (_state.value !is AppUpdateState.Downloading && _state.value !is AppUpdateState.Installing) {
            _state.value = AppUpdateState.Idle
        }
    }

    /** Call from Activity.onResume after Android settings/package-installer returns to the app. */
    fun onActivityResumed() {
        val pending = pendingInstall
        if (pending != null && canInstallPackages()) {
            launchPackageInstaller(pending.apkFile, pending.versionName)
            return
        }

        // If the installer was opened and the user came back without completing it, do not leave a
        // permanent "Installing" dialog over the call screen. Re-check; a successful install will
        // normally restart/replace this process and the new version will report itself current.
        if (pending == null && _state.value is AppUpdateState.Installing) {
            checkForUpdates()
        }
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

        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) continue

            when {
                name.endsWith(".apk", ignoreCase = true) && name.startsWith("AlShorti-") -> apkUrl = url
                name.endsWith(".apk.sha256", ignoreCase = true) && name.startsWith("AlShorti-") -> shaUrl = url
            }
        }

        if (apkUrl == null || shaUrl == null) return null

        return AppUpdateState.Available(
            versionName = tag,
            apkUrl = apkUrl,
            sha256Url = shaUrl,
            releaseNotes = json.optString("body").take(UPDATE_NOTES_LIMIT)
        )
    }

    private fun downloadAndVerify(release: AppUpdateState.Available): File {
        val updateDir = File(activity.externalCacheDir ?: activity.cacheDir, "updates").apply { mkdirs() }
        updateDir.listFiles()?.forEach { old ->
            if (old.name != "AlShorti-${release.versionName}.apk") runCatching { old.delete() }
        }

        val target = File(updateDir, "AlShorti-${release.versionName}.apk")
        val partial = File(updateDir, "${target.name}.part")
        runCatching { partial.delete() }

        val connection = openConnection(release.apkUrl)
        val response = connection.responseCode
        if (response !in 200..299) {
            connection.disconnect()
            error("تعذر تنزيل ملف التحديث.")
        }

        val totalBytes = connection.contentLengthLong
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

                    if (totalBytes > 0L) {
                        val progress = ((copied.toDouble() / totalBytes.toDouble()) * 100.0)
                            .roundToInt()
                            .coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            _state.value = AppUpdateState.Downloading(release.versionName, progress)
                        }
                    } else if (lastProgress != 0) {
                        lastProgress = 0
                        _state.value = AppUpdateState.Downloading(release.versionName, 0)
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

        val expectedHash = downloadText(release.sha256Url)
            .trim()
            .substringBefore(' ')
            .lowercase()

        require(expectedHash.matches(Regex("[0-9a-f]{64}"))) {
            "ملف التحقق من التحديث غير صالح."
        }

        val actualHash = sha256(target)
        if (actualHash != expectedHash) {
            target.delete()
            error("فشل التحقق من سلامة التحديث؛ لم يتم فتح ملف التثبيت.")
        }

        if (!hasSameSigningCertificate(target)) {
            target.delete()
            throw SigningMismatchException(
                "هذه النسخة الحالية موقعة بمفتاح اختبار مختلف عن التحديث الرسمي. " +
                    "يلزم الانتقال مرة واحدة إلى النسخة الموقعة رسميًا؛ بعد ذلك ستثبت جميع التحديثات فوق السابقة بدون حذف."
            )
        }

        return target
    }

    private fun requestInstall(apkFile: File, versionName: String) {
        if (canInstallPackages()) {
            launchPackageInstaller(apkFile, versionName)
            return
        }

        _state.value = AppUpdateState.PermissionRequired(versionName)
        openUnknownSourcesSettings()
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

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.updates",
            apkFile
        )

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
                    _state.value = AppUpdateState.Error(
                        "تعذر فتح مثبت Android. أعد المحاولة.",
                        retryable = true
                    )
                }
        }
    }

    private fun hasSameSigningCertificate(apkFile: File): Boolean {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = activity.packageManager.getPackageInfo(activity.packageName, flags)
        val candidate = activity.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return false

        val installedDigests = installed.signingInfo?.apkContentsSigners
            ?.map { certificate -> sha256(certificate.toByteArray()) }
            ?.toSet()
            .orEmpty()
        val candidateDigests = candidate.signingInfo?.apkContentsSigners
            ?.map { certificate -> sha256(certificate.toByteArray()) }
            ?.toSet()
            .orEmpty()

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
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

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
