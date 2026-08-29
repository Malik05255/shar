package com.almi.ai.data.repository

import com.almi.ai.data.network.NetworkClient
import io.ktor.client.request.get
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveredProvider(
    val id: String,
    val name: String,
    val supportsText: Boolean,
    val supportsImage: Boolean,
    val supportsVideo: Boolean,
    val reachable: Boolean,
    val connected: Boolean,
    val integrated: Boolean,
    val requiresPersonalApiKey: Boolean,
    val score: Int,
    val eligibleForAutomaticFree: Boolean = true,
)

data class ProviderDiscoveryResult(
    val providers: List<DiscoveredProvider> = emptyList(),
    val checkedAt: Long = System.currentTimeMillis(),
    val scannedCount: Int = 0,
    val excludedCount: Int = 0,
) {
    val connectedProvider: DiscoveredProvider?
        get() = providers.firstOrNull {
            it.connected &&
                it.integrated &&
                it.eligibleForAutomaticFree &&
                !it.requiresPersonalApiKey
        }
}

/**
 * Broad discovery for ALMI's "Free AI" mode.
 *
 * ALMI probes a wider catalogue of well-known providers, but the automatic no-key pool only keeps
 * providers that genuinely work without the user creating/pasting a personal API key and for which
 * ALMI has a real runtime adapter. Services with a free allowance but mandatory credentials are
 * deliberately counted as scanned/excluded instead of being presented as automatic-free.
 *
 * This keeps discovery honest while allowing the registry to grow without changing UI semantics.
 */
class ProviderDiscoveryRepository @Inject constructor(
    private val networkClient: NetworkClient,
) {
    suspend fun discoverTop(limit: Int = 10): Result<ProviderDiscoveryResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val checked = coroutineScope {
                    DISCOVERY_REGISTRY.map { entry ->
                        async {
                            val reachable = probe(entry.probeUrl)
                            val eligible = entry.eligibleForAutomaticFree && !entry.requiresPersonalApiKey
                            val connected = reachable && entry.integrated && eligible
                            DiscoveredProvider(
                                id = entry.id,
                                name = entry.name,
                                supportsText = entry.supportsText,
                                supportsImage = entry.supportsImage,
                                supportsVideo = entry.supportsVideo,
                                reachable = reachable,
                                connected = connected,
                                integrated = entry.integrated,
                                requiresPersonalApiKey = entry.requiresPersonalApiKey,
                                eligibleForAutomaticFree = entry.eligibleForAutomaticFree,
                                score = entry.baseScore +
                                    if (connected) 50 else if (reachable) 10 else -50,
                            )
                        }
                    }.awaitAll()
                }

                val eligible = checked
                    .filter { it.eligibleForAutomaticFree && !it.requiresPersonalApiKey }
                    .sortedWith(
                        compareByDescending<DiscoveredProvider> { it.connected }
                            .thenByDescending { it.score }
                            .thenBy { it.name }
                    )
                    .take(limit.coerceIn(1, 12))

                ProviderDiscoveryResult(
                    providers = eligible,
                    scannedCount = checked.size,
                    excludedCount = checked.size - eligible.size,
                )
            }
        }

    private suspend fun probe(url: String): Boolean =
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                val response = networkClient().get(url)
                response.status.value in 200..399
            }.getOrDefault(false)
        } ?: false

    private data class RegistryEntry(
        val id: String,
        val name: String,
        val probeUrl: String,
        val supportsText: Boolean,
        val supportsImage: Boolean,
        val supportsVideo: Boolean,
        val integrated: Boolean,
        val requiresPersonalApiKey: Boolean,
        val eligibleForAutomaticFree: Boolean,
        val baseScore: Int,
    )

    companion object {
        const val AI_HORDE_ID = "ai-horde"
        const val AI_HORDE_ANONYMOUS_KEY = "0000000000"
        const val AI_HORDE_OPENAI_BASE_URL = "https://oai.aihorde.net"
        private const val PROBE_TIMEOUT_MS = 5_000L

        /**
         * Registry intentionally includes excluded candidates. This makes discovery broad while the
         * returned automatic-free pool remains strict. Add a provider as eligible only after both
         * no-personal-key access and a working ALMI adapter are verified.
         */
        private val DISCOVERY_REGISTRY = listOf(
            RegistryEntry(
                id = AI_HORDE_ID,
                name = "AI Horde",
                probeUrl = "$AI_HORDE_OPENAI_BASE_URL/heartbeat",
                supportsText = true,
                // The public service supports image generation, but ALMI does not silently route
                // private body photos through community workers. Keep Try-On image capability off
                // until an explicit privacy/consent route is added.
                supportsImage = false,
                supportsVideo = false,
                integrated = true,
                requiresPersonalApiKey = false,
                eligibleForAutomaticFree = true,
                baseScore = 120,
            ),
            RegistryEntry(
                id = "openrouter-free",
                name = "OpenRouter Free",
                probeUrl = "https://openrouter.ai/api/v1/models",
                supportsText = true,
                supportsImage = false,
                supportsVideo = true,
                integrated = true,
                requiresPersonalApiKey = true,
                eligibleForAutomaticFree = false,
                baseScore = 100,
            ),
            RegistryEntry(
                id = "huggingface-inference",
                name = "Hugging Face Inference",
                probeUrl = "https://huggingface.co/api/models?limit=1",
                supportsText = true,
                supportsImage = true,
                supportsVideo = false,
                integrated = false,
                requiresPersonalApiKey = true,
                eligibleForAutomaticFree = false,
                baseScore = 90,
            ),
            RegistryEntry(
                id = "cloudflare-workers-ai",
                name = "Cloudflare Workers AI",
                probeUrl = "https://developers.cloudflare.com/workers-ai/models/",
                supportsText = true,
                supportsImage = true,
                supportsVideo = false,
                integrated = false,
                requiresPersonalApiKey = true,
                eligibleForAutomaticFree = false,
                baseScore = 88,
            ),
            RegistryEntry(
                id = "pollinations",
                name = "Pollinations",
                probeUrl = "https://gen.pollinations.ai/v1/models",
                supportsText = true,
                supportsImage = true,
                supportsVideo = true,
                integrated = false,
                requiresPersonalApiKey = true,
                eligibleForAutomaticFree = false,
                baseScore = 84,
            ),
            RegistryEntry(
                id = "puter",
                name = "Puter",
                probeUrl = "https://js.puter.com/v2/",
                supportsText = true,
                supportsImage = true,
                supportsVideo = true,
                integrated = false,
                requiresPersonalApiKey = false,
                // Puter has no developer API key, but its model is user-auth/user-pays rather than
                // anonymous free inference, so it is not silently mixed into ALMI's free pool.
                eligibleForAutomaticFree = false,
                baseScore = 82,
            ),
            RegistryEntry(
                id = "google-ai-studio",
                name = "Google AI Studio",
                probeUrl = "https://ai.google.dev/gemini-api/docs/pricing",
                supportsText = true,
                supportsImage = true,
                supportsVideo = true,
                integrated = false,
                requiresPersonalApiKey = true,
                eligibleForAutomaticFree = false,
                baseScore = 80,
            ),
            RegistryEntry(
                id = "groq-free-tier",
                name = "Groq Free Tier",
                probeUrl = "https://console.groq.com/docs/rate-limits",
                supportsText = true,
                supportsImage = false,
                supportsVideo = false,
                integrated = false,
                requiresPersonalApiKey = true,
                eligibleForAutomaticFree = false,
                baseScore = 76,
            ),
        )
    }
}
