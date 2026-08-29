package com.almi.ai.data.repository

import com.almi.ai.data.network.NetworkClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class GoogleModelSpeed {
    VERY_FAST,
    FAST,
    BALANCED,
    QUALITY,
}

enum class GoogleOutputKind {
    TEXT,
    IMAGE,
    VIDEO,
}

data class GoogleAiStudioModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val outputKind: GoogleOutputKind,
    val acceptsMedia: Boolean,
    val freeTierAvailable: Boolean,
    val paidTierAvailable: Boolean,
    val speed: GoogleModelSpeed,
    val paidPriceLabel: String,
    val inputTokenLimit: Int?,
    val outputTokenLimit: Int?,
)

data class GoogleAiStudioCatalog(
    val freeModels: List<GoogleAiStudioModelInfo> = emptyList(),
    val paidModels: List<GoogleAiStudioModelInfo> = emptyList(),
) {
    val allModels: List<GoogleAiStudioModelInfo>
        get() = (freeModels + paidModels).distinctBy { it.id }
}

class GoogleAiStudioRepository @Inject constructor(
    private val networkClient: NetworkClient,
) {
    suspend fun connect(apiKey: String): Result<GoogleAiStudioCatalog> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = apiKey.trim()
            require(normalized.isNotBlank()) { "google_api_key_missing" }

            val models = fetchModels(normalized)
            if (models.isEmpty()) throw IllegalStateException("google_models_empty")

            GoogleAiStudioCatalog(
                freeModels = models
                    .filter { it.freeTierAvailable }
                    .sortedWith(compareBy<GoogleAiStudioModelInfo> { it.speed.ordinal }.thenBy { it.name }),
                paidModels = models
                    .filter { it.paidTierAvailable }
                    .sortedWith(compareBy<GoogleAiStudioModelInfo> { it.speed.ordinal }.thenBy { it.name }),
            )
        }
    }

    private suspend fun fetchModels(apiKey: String): List<GoogleAiStudioModelInfo> {
        val collected = mutableListOf<GoogleAiStudioModelInfo>()
        var pageToken: String? = null
        var page = 0

        do {
            val url = buildString {
                append(MODELS_URL)
                append("?pageSize=1000")
                pageToken?.takeIf(String::isNotBlank)?.let {
                    append("&pageToken=")
                    append(java.net.URLEncoder.encode(it, Charsets.UTF_8.name()))
                }
            }
            val response = networkClient().get(url) {
                header("x-goog-api-key", apiKey)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException(
                    "google_connect_${response.status.value}:${message.take(180)}"
                )
            }

            val root = JSONObject(body)
            val array = root.optJSONArray("models") ?: JSONArray()
            for (index in 0 until array.length()) {
                parseModel(array.optJSONObject(index) ?: continue)?.let(collected::add)
            }
            pageToken = root.optString("nextPageToken").takeIf(String::isNotBlank)
            page++
        } while (pageToken != null && page < 3)

        return collected.distinctBy { it.id }
    }

    private fun parseModel(item: JSONObject): GoogleAiStudioModelInfo? {
        val resourceName = item.optString("name").trim()
        val id = resourceName.removePrefix("models/").trim()
        if (id.isBlank()) return null

        val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
        val supportsGeneration = (0 until methods.length()).any {
            val value = methods.optString(it)
            value.equals("generateContent", ignoreCase = true) ||
                value.equals("predictLongRunning", ignoreCase = true)
        }
        val looksLikeVideoGenerator = id.startsWith("veo-", ignoreCase = true) ||
            id.contains("omni", ignoreCase = true)
        if (!supportsGeneration && !looksLikeVideoGenerator) return null

        if (
            id.contains("embedding", true) ||
            id.contains("tts", true) ||
            id.contains("transcribe", true) ||
            id.contains("robotics", true) ||
            id.contains("computer-use", true) ||
            id.contains("deep-research", true)
        ) return null

        val outputKind = when {
            looksLikeVideoGenerator -> GoogleOutputKind.VIDEO
            id.contains("image", true) || id.startsWith("imagen-", true) -> GoogleOutputKind.IMAGE
            else -> GoogleOutputKind.TEXT
        }
        val pricing = pricingFor(id)

        return GoogleAiStudioModelInfo(
            id = id,
            name = item.optString("displayName").ifBlank { friendlyName(id) },
            description = item.optString("description").trim(),
            outputKind = outputKind,
            acceptsMedia = acceptsMedia(id, outputKind),
            freeTierAvailable = pricing.freeTier,
            paidTierAvailable = pricing.paidTier,
            speed = speedFor(id),
            paidPriceLabel = pricing.label,
            inputTokenLimit = item.optInt("inputTokenLimit", -1).takeIf { it > 0 },
            outputTokenLimit = item.optInt("outputTokenLimit", -1).takeIf { it > 0 },
        )
    }

    private fun acceptsMedia(id: String, outputKind: GoogleOutputKind): Boolean = when (outputKind) {
        GoogleOutputKind.IMAGE, GoogleOutputKind.VIDEO -> true
        GoogleOutputKind.TEXT -> id.startsWith("gemini-", true)
    }

    private fun speedFor(id: String): GoogleModelSpeed = when {
        id.contains("flash-lite", true) || id.contains("veo-3.1-lite", true) -> GoogleModelSpeed.VERY_FAST
        id.contains("flash", true) || id.contains("fast", true) -> GoogleModelSpeed.FAST
        id.contains("pro", true) -> GoogleModelSpeed.QUALITY
        else -> GoogleModelSpeed.BALANCED
    }

    private data class Pricing(
        val freeTier: Boolean,
        val paidTier: Boolean,
        val label: String,
    )

    /**
     * Pricing labels are deliberately limited to models with clearly published Google Developer API
     * rates. Unknown/new models stay visible after models.list but show "See Google pricing" rather
     * than a guessed price.
     */
    private fun pricingFor(id: String): Pricing = when {
        id == "gemini-3.7-flash" -> Pricing(true, true, "$0.75 input / $3.75 output per 1M tokens")
        id == "gemini-3.1-flash-lite" -> Pricing(true, true, "$0.25 input / $1.50 output per 1M tokens")
        id == "gemini-2.5-flash" -> Pricing(true, true, "$0.30 input / $2.50 output per 1M tokens")
        id == "gemini-2.5-flash-lite" -> Pricing(true, true, "$0.10 input / $0.40 output per 1M tokens")
        id == "gemini-2.5-pro" -> Pricing(true, true, "$1.25 input / $10 output per 1M tokens")
        id == "gemini-3.1-flash-image" -> Pricing(false, true, "$0.067 per 1K image")
        id == "gemini-3.1-flash-lite-image" -> Pricing(false, true, "$0.0336 per 1K image")
        id == "gemini-2.5-flash-image" -> Pricing(false, true, "$0.039 per 1K image")
        id.startsWith("veo-3.1-fast", true) -> Pricing(false, true, "$0.10/sec at 720p")
        id.startsWith("veo-3.1-lite", true) -> Pricing(false, true, "$0.05/sec at 720p")
        id.startsWith("veo-3.1-", true) -> Pricing(false, true, "$0.40/sec at 720p/1080p")
        id.contains("image", true) || id.startsWith("veo-", true) || id.contains("omni", true) ->
            Pricing(false, true, "See Google pricing")
        id.startsWith("gemini-", true) -> Pricing(true, true, "See Google pricing")
        else -> Pricing(false, true, "See Google pricing")
    }

    private fun friendlyName(id: String): String = id
        .replace('-', ' ')
        .split(' ')
        .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val MODELS_URL = "$BASE_URL/models"
    }
}
