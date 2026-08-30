package com.malik.alshurti.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Finds the self-hosted Al-Shorti backend advertised as _alshorti._tcp on the LAN. */
class BackendLocator(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile private var cachedBaseUrl: String? = null

    suspend fun resolve(force: Boolean = false): String {
        if (!force) cachedBaseUrl?.let { if (ready(it)) return it }

        val discovered = runCatching { discoverWithNsd() }.getOrNull()
        val candidates = buildList {
            if (!discovered.isNullOrBlank()) add(discovered)
            add("http://alshorti.local:8787")
        }.distinct()

        var warmingServer: String? = null
        for (candidate in candidates) {
            if (!health(candidate)) continue
            if (ready(candidate)) {
                cachedBaseUrl = candidate
                return candidate
            }
            warmingServer = candidate
        }

        if (warmingServer != null && awaitReady(warmingServer)) {
            cachedBaseUrl = warmingServer
            return warmingServer
        }

        if (warmingServer != null) {
            error("خادم الشرطي متصل لكن نماذج الصوت والمحادثة لم تجهز بعد. انتظر قليلاً ثم أعد المحاولة.")
        }
        error("خادم الصوت الحقيقي غير متصل. شغّل Al-Shorti Voice Backend على نفس شبكة الواي فاي.")
    }

    fun invalidate() {
        cachedBaseUrl = null
    }

    private suspend fun awaitReady(baseUrl: String): Boolean {
        repeat(READY_RETRIES) {
            if (ready(baseUrl)) return true
            delay(READY_RETRY_MS)
        }
        return false
    }

    private suspend fun discoverWithNsd(): String = withTimeout(DISCOVERY_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            lateinit var discoveryListener: NsdManager.DiscoveryListener

            fun finish(block: () -> Unit) {
                if (!finished.compareAndSet(false, true)) return
                runCatching { nsd.stopServiceDiscovery(discoveryListener) }
                block()
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains("_alshorti._tcp")) return
                    @Suppress("DEPRECATION")
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host ?: return
                            val rawAddress = host.hostAddress ?: return
                            val address = if (host is Inet6Address) "[$rawAddress]" else rawAddress
                            val port = resolved.port.takeIf { it > 0 } ?: 8787
                            finish {
                                if (continuation.isActive) continuation.resume("http://$address:$port")
                            }
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) {
                    finish {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("توقف اكتشاف خادم الصوت.")
                        )
                    }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    finish {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("تعذر البحث عن خادم الصوت ($errorCode).")
                        )
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    runCatching { nsd.stopServiceDiscovery(discoveryListener) }
                }
            }

            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
    }

    private fun health(baseUrl: String): Boolean = probe("$baseUrl/v1/health")
    private fun ready(baseUrl: String): Boolean = probe("$baseUrl/v1/ready")

    private fun probe(url: String): Boolean {
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1_500
                readTimeout = 2_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
        }.getOrNull() ?: return false

        return try {
            connection.connect()
            connection.responseCode in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_alshorti._tcp."
        const val DISCOVERY_TIMEOUT_MS = 6_000L
        const val READY_RETRIES = 60
        const val READY_RETRY_MS = 1_500L
    }
}
