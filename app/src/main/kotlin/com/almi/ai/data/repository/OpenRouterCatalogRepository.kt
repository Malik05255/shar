package com.almi.ai.data.repository

import com.almi.ai.data.network.NetworkClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ModelCapability {
    TEXT,
    IMAGE,
    VIDEO,
}

data class OpenRouterModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val capabilities: Set<ModelCapability>,
    val isFree: Boolean,
    val created: Long,
    val contextLength: Int?,
    val inputUsdPerMillion: Double?,
    val outputUsdPerMillion: Double?,
    val imageUsdPerUnit: Double?,
    val videoUsdPerSecond: Double?,
) {
    fun supports(capability: ModelCapability): Boolean = capability in capabilities

    /**
     * A deliberately conservative estimate for a small product-analysis/chat turn.
     * It is shown as an estimate only; actual token usage can differ substantially.
     */
    fun estimatedTextTurnCostUsd(): Double? {
        val input = inputUsdPerMillion ?: return null
        val output = outputUsdPerMillion ?: return null
        return input * 0.001 + output * 0.0005
    }

    fun estimatedImageCount(remainingUsd: Double?): Int? {
        val price = imageUsdPerUnit ?: return null
        if (remainingUsd == null || price <= 0.0) return null
        return (remainingUsd / price).toInt().coerceAtLeast(0)
    }

    fun estimatedFourSecondVideoCount(remainingUsd: Double?): Int? {
        val price = videoUsdPerSecond ?: return null
        if (remainingUsd == null || price <= 0.0) return null
        return (remainingUsd / (price * 4.0)).toInt().coerceAtLeast(0)
    }

    fun estimatedTextTurnCount(remainingUsd: Double?): Int? {
        val price = estimatedTextTurnCostUsd() ?: return null
        if (remainingUsd == null || price <= 0.0) return null
        return (remainingUsd / price).toInt().coerceAtLeast(0)
    }
}

data class OpenRouterCatalog(
    val textModels: List<OpenRouterModelInfo> = emptyList(),
    val imageModels: List<OpenRouterModelInfo> = emptyList(),
    val videoModels: List<OpenRouterModelInfo> = emptyList(),
) {
    fun filtered(freeOnly: Boolean): OpenRouterCatalog {
        if (!freeOnly) return this
        return copy(
            textModels = textModels.filter { it.isFree },
            imageModels = imageModels.filter { it.isFree },
            videoModels = videoModels.filter { it.isFree },
        )
    }
}

data class OpenRouterKeyStatus(
    val connected: Boolean = false,
    val label: String = "",
    val freeTier: Boolean = false,
    val usageUsd: Double? = null,
    val limitUsd: Double? = null,
    val remainingUsd: Double? = null,
    val limitReset: String? = null,
    val expiresAt: String? = null,
)

class OpenRouterCatalogRepository @Inject constructor(
    private val networkClient: NetworkClient,
) {
    suspend fun loadCatalog(apiKey: String? = null): Result<OpenRouterCatalog> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = fetchModels(TEXT_MODELS_URL, ModelCapability.TEXT, apiKey)
                val image = fetchModels(IMAGE_MODELS_URL, ModelCapability.IMAGE, apiKey)
                val video = fetchModels(VIDEO_MODELS_URL, ModelCapability.VIDEO, apiKey)

                OpenRouterCatalog(
                    textModels = text.distinctBy { it.id }.sortedModels(),
                    imageModels = image.distinctBy { it.id }.sortedModels(),
                    videoModels = video.distinctBy { it.id }.sortedModels(),
                )
            }
        }

    suspend fun loadKeyStatus(apiKey: String): Result<OpenRouterKeyStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(apiKey.isNotBlank()) { "openrouter_key_missing" }
                val response = networkClient().get(KEY_STATUS_URL) { bearerAuth(apiKey) }
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("openrouter_key_status_${response.status.value}")
                }
                val data = JSONObject(body).optJSONObject("data") ?: JSONObject()
                OpenRouterKeyStatus(
                    connected = true,
                    label = data.optString("label"),
                    freeTier = data.optBoolean("is_free_tier", false),
                    usageUsd = data.optNullableDouble("usage"),
                    limitUsd = data.optNullableDouble("limit"),
                    remainingUsd = data.optNullableDouble("limit_remaining"),
                    limitReset = data.optString("limit_reset").takeIf(String::isNotBlank),
                    expiresAt = data.optString("expires_at").takeIf(String::isNotBlank),
                )
            }
        }

    private suspend fun fetchModels(
        url: String,
        capability: ModelCapability,
        apiKey: String?,
    ): List<OpenRouterModelInfo> {
        val response = networkClient().get(url) {
            apiKey?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
        }
        if (!response.status.isSuccess()) return emptyList()
        val root = runCatching { JSONObject(response.bodyAsText()) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                parseModel(item, capability)?.let(::add)
            }
        }
    }

    private fun parseModel(item: JSONObject, primaryCapability: ModelCapability): OpenRouterModelInfo? {
        val id = item.optString("id").trim()
        if (id.isBlank()) return null
        val name = item.optString("name").ifBlank { id }
        val architecture = item.optJSONObject("architecture")
        val declaredOutputs = architecture?.optJSONArray("output_modalities")
        val capabilities = buildSet {
            add(primaryCapability)
            if (declaredOutputs != null) {
                for (index in 0 until declaredOutputs.length()) {
                    when (declaredOutputs.optString(index).lowercase()) {
                        "text" -> add(ModelCapability.TEXT)
                        "image" -> add(ModelCapability.IMAGE)
                        "video" -> add(ModelCapability.VIDEO)
                    }
                }
            }
        }

        val pricingObject = item.optJSONObject("pricing")
        val pricingArray = item.optJSONArray("pricing")
        val promptPerToken = pricingObject?.optNullableDouble("prompt")
        val completionPerToken = pricingObject?.optNullableDouble("completion")
        val imagePerUnitFromObject = pricingObject?.optNullableDouble("image")
        val imagePerUnitFromArray = pricingArray.findPrice(
            billableContains = "image",
            units = setOf("image", "output_image"),
        )
        val videoPerSecond = pricingArray.findPrice(
            billableContains = "video",
            units = setOf("second", "video_second", "output_second"),
        )
        val numericPrices = buildList {
            promptPerToken?.let(::add)
            completionPerToken?.let(::add)
            imagePerUnitFromObject?.let(::add)
            imagePerUnitFromArray?.let(::add)
            videoPerSecond?.let(::add)
            if (pricingArray != null) {
                for (index in 0 until pricingArray.length()) {
                    pricingArray.optJSONObject(index)?.optNullableDouble("cost_usd")?.let(::add)
                }
            }
        }
        val explicitlyFree = id.endsWith(":free", ignoreCase = true) ||
            name.contains("(free)", ignoreCase = true) ||
            name.contains(" free", ignoreCase = true)
        val isFree = explicitlyFree || (numericPrices.isNotEmpty() && numericPrices.all { it <= 0.0 })

        return OpenRouterModelInfo(
            id = id,
            name = name,
            description = item.optString("description").trim(),
            capabilities = capabilities,
            isFree = isFree,
            created = item.optLong("created", 0L),
            contextLength = item.optInt("context_length", -1).takeIf { it > 0 },
            inputUsdPerMillion = promptPerToken?.times(1_000_000.0),
            outputUsdPerMillion = completionPerToken?.times(1_000_000.0),
            imageUsdPerUnit = imagePerUnitFromArray ?: imagePerUnitFromObject,
            videoUsdPerSecond = videoPerSecond,
        )
    }

    private fun List<OpenRouterModelInfo>.sortedModels(): List<OpenRouterModelInfo> =
        sortedWith(
            compareByDescending<OpenRouterModelInfo> { it.isFree }
                .thenByDescending { it.created }
                .thenBy { it.name.lowercase() }
        )

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONArray?.findPrice(
        billableContains: String,
        units: Set<String>,
    ): Double? {
        if (this == null) return null
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val billable = item.optString("billable").lowercase()
            val unit = item.optString("unit").lowercase()
            if (billable.contains(billableContains) && (unit in units || units.isEmpty())) {
                item.optNullableDouble("cost_usd")?.let { return it }
            }
        }
        return null
    }

    companion object {
        const val BASE_URL = "https://openrouter.ai/api/v1"
        const val IMAGES_URL = "$BASE_URL/images"
        const val VIDEOS_URL = "$BASE_URL/videos"
        const val CHAT_URL = "$BASE_URL/chat/completions"
        private const val TEXT_MODELS_URL = "$BASE_URL/models?output_modalities=text&sort=most-popular"
        private const val IMAGE_MODELS_URL = "$BASE_URL/images/models"
        private const val VIDEO_MODELS_URL = "$BASE_URL/videos/models"
        private const val KEY_STATUS_URL = "$BASE_URL/key"
    }
}
