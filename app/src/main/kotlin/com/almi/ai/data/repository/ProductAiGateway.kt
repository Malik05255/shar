package com.almi.ai.data.repository

import com.almi.ai.data.model.ProductExtractionSource
import com.almi.ai.data.model.ProductPreview
import com.almi.ai.data.network.NetworkClient
import com.almi.ai.data.preferences.AiMode
import com.almi.ai.data.preferences.AlmiPreferences
import com.almi.ai.data.preferences.ApiKeyVault
import com.almi.ai.data.preferences.CustomAiConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ProductAiGateway @Inject constructor(
    private val networkClient: NetworkClient,
    private val preferences: AlmiPreferences,
    private val apiKeyVault: ApiKeyVault,
    private val openRouterCatalogRepository: OpenRouterCatalogRepository,
) {
    suspend fun enrich(
        requestedUrl: String,
        local: ProductPageSnapshot?,
    ): Result<ProductPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = normalizeProductUrl(requestedUrl)
            when (preferences.currentAiMode()) {
                AiMode.OPENROUTER -> requestOpenRouter(normalizedUrl, local)
                AiMode.FREE_AUTO -> requestAutomaticNoKey(normalizedUrl, local)
                AiMode.CUSTOM -> requestCustom(normalizedUrl, local)
            }
        }
    }

    private suspend fun requestOpenRouter(url: String, local: ProductPageSnapshot?): ProductPreview {
        val keys = apiKeyVault.activeOpenRouterKeys()
        if (keys.isEmpty()) throw IllegalStateException("openrouter_api_key_missing")
        val config = preferences.currentOpenRouterConfig()
        val catalog = openRouterCatalogRepository.loadCatalog(keys.first().secret).getOrElse {
            throw IllegalStateException("openrouter_catalog_failed", it)
        }.filtered(config.freeOnly)

        val models = buildList {
            config.analysisModel.takeIf(String::isNotBlank)?.let(::add)
            addAll(catalog.textModels.map { it.id })
            if (config.freeOnly && none { it == FREE_ANALYSIS_MODEL }) add(FREE_ANALYSIS_MODEL)
        }.distinct()

        var lastError: Throwable? = null
        for (model in models) {
            for (key in keys) {
                try {
                    return requestAnalysis(
                        endpoint = OpenRouterCatalogRepository.CHAT_URL,
                        apiKey = key.secret,
                        model = model,
                        url = url,
                        local = local,
                        enableOpenRouterWebFetch = true,
                    )
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }
        throw IllegalStateException("openrouter_analysis_fallback_exhausted", lastError)
    }

    /**
     * Free mode is genuinely no-personal-key. It uses AI Horde's OpenAI-compatible anonymous text
     * route and never reads a stored OpenRouter key. Because this route has no web-fetch tool,
     * ALMI sends only page data already fetched deterministically on-device. If the store blocked
     * all local extraction, we stop instead of asking the model to guess from a URL.
     */
    private suspend fun requestAutomaticNoKey(
        url: String,
        local: ProductPageSnapshot?,
    ): ProductPreview {
        val hasLocalFacts = local?.let {
            it.readableText.isNotBlank() ||
                it.preview.title.isNotBlank() ||
                it.preview.imageUrl != null ||
                it.preview.description.isNotBlank()
        } == true
        if (!hasLocalFacts) throw IllegalStateException("free_text_context_unavailable")

        val model = firstAnonymousTextModel()
        return requestAnalysis(
            endpoint = "${ProviderDiscoveryRepository.AI_HORDE_OPENAI_BASE_URL}/v1/chat/completions",
            apiKey = ProviderDiscoveryRepository.AI_HORDE_ANONYMOUS_KEY,
            model = model,
            url = url,
            local = local,
            enableOpenRouterWebFetch = false,
        )
    }

    private suspend fun firstAnonymousTextModel(): String {
        val response = networkClient().get(
            "${ProviderDiscoveryRepository.AI_HORDE_OPENAI_BASE_URL}/v1/models"
        )
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("free_text_models_http_${response.status.value}")
        }
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IllegalStateException("free_text_models_invalid")
        val data = root.optJSONArray("data") ?: throw IllegalStateException("free_text_models_empty")
        for (index in 0 until data.length()) {
            val id = data.optJSONObject(index)?.optString("id")?.trim().orEmpty()
            if (id.isNotBlank()) return id
        }
        throw IllegalStateException("free_text_models_empty")
    }

    private suspend fun requestCustom(url: String, local: ProductPageSnapshot?): ProductPreview {
        val secret = apiKeyVault.activeCustomProviderKey()?.secret
            ?: throw IllegalStateException("custom_api_key_missing")
        val config = preferences.currentCustomAiConfig().copy(apiKey = secret)
        if (!config.canAnalyzeProducts) throw IllegalStateException("custom_analysis_missing")
        return requestAnalysis(
            endpoint = resolveEndpoint(config.baseUrl, config.analysisEndpoint),
            apiKey = secret,
            model = config.analysisModel,
            url = url,
            local = local,
            enableOpenRouterWebFetch = isOpenRouter(config),
        )
    }

    private suspend fun requestAnalysis(
        endpoint: String,
        apiKey: String,
        model: String,
        url: String,
        local: ProductPageSnapshot?,
        enableOpenRouterWebFetch: Boolean,
    ): ProductPreview {
        val host = URI(url).host.orEmpty().removePrefix("www.")
        val localPreview = local?.preview
        val localContext = buildString {
            if (localPreview != null) {
                appendLine("Deterministic extraction already found:")
                appendLine("title=${localPreview.title}")
                appendLine("brand=${localPreview.brand}")
                appendLine("price=${localPreview.price}")
                appendLine("currency=${localPreview.currency}")
                appendLine("color=${localPreview.color}")
                appendLine("sku=${localPreview.sku}")
                appendLine("image=${localPreview.imageUrl.orEmpty()}")
            }
            val text = local?.readableText.orEmpty().take(MAX_LOCAL_CONTEXT_CHARS)
            if (text.isNotBlank()) {
                appendLine("Visible page text:")
                append(text)
            }
        }.take(MAX_LOCAL_CONTEXT_CHARS)

        val request = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "You are a product-page extraction engine. Extract factual product data only. " +
                                    "Never invent a product image URL, price, brand, color, SKU, or description. " +
                                    "Return exactly one JSON object and no markdown."
                            )
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", buildProductPrompt(url, localContext, enableOpenRouterWebFetch))
                    )
            )
            .put("temperature", 0.0)

        if (enableOpenRouterWebFetch) {
            request.put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "openrouter:web_fetch")
                        .put(
                            "parameters",
                            JSONObject()
                                .put("engine", "openrouter")
                                .put("max_uses", 1)
                                .put("max_content_tokens", MAX_WEB_FETCH_TOKENS)
                                .put("allowed_domains", JSONArray().put(host))
                        )
                )
            )
            request.put(
                "provider",
                JSONObject()
                    .put("allow_fallbacks", true)
                    .put("sort", "throughput")
            )
        }

        val response = networkClient().post(endpoint) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            if (enableOpenRouterWebFetch) applyOpenRouterHeaders()
            setBody(request.toString())
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("product_ai_http_${response.status.value}:${extractApiMessage(body)}")
        }

        val root = JSONObject(body)
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("product_ai_empty_response")
        val content = messageText(message)
        val json = extractJsonObject(content)
            ?: throw IllegalStateException("product_ai_invalid_json")

        return mergeAiResult(url, host, local, json)
    }

    private fun mergeAiResult(
        requestedUrl: String,
        host: String,
        local: ProductPageSnapshot?,
        ai: JSONObject,
    ): ProductPreview {
        val base = local?.preview
        val aiImages = buildList {
            ai.optString("image_url").takeIf(::isHttpUrl)?.let(::add)
            val array = ai.optJSONArray("images")
            if (array != null) {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(::isHttpUrl)?.let(::add)
                }
            }
        }.distinct().take(MAX_AI_IMAGES)

        val preferAiPrimary = base?.imageUrl == null || (local?.confidence ?: 0f) < 0.55f
        val primaryImage = if (preferAiPrimary) aiImages.firstOrNull() ?: base?.imageUrl else base?.imageUrl ?: aiImages.firstOrNull()
        val allImages = buildList {
            primaryImage?.let(::add)
            addAll(base?.images.orEmpty())
            addAll(aiImages)
        }.distinct().take(MAX_AI_IMAGES)

        val localTitleIsWeak = base == null ||
            base.title.isBlank() ||
            base.title.equals("Product", ignoreCase = true) ||
            base.title.equals(base.merchant, ignoreCase = true)

        val sourceUrl = ai.optString("source_url")
            .takeIf(::isHttpUrl)
            ?: base?.sourceUrl
            ?: requestedUrl

        return ProductPreview(
            sourceUrl = sourceUrl,
            title = if (localTitleIsWeak) ai.clean("title").ifBlank { base?.title.orEmpty() } else base?.title.orEmpty(),
            imageUrl = primaryImage,
            merchant = base?.merchant?.ifBlank { host } ?: host,
            description = base?.description?.ifBlank { ai.clean("description") } ?: ai.clean("description"),
            brand = base?.brand?.ifBlank { ai.clean("brand") } ?: ai.clean("brand"),
            price = base?.price?.ifBlank { ai.clean("price") } ?: ai.clean("price"),
            currency = base?.currency?.ifBlank { ai.clean("currency") } ?: ai.clean("currency"),
            color = base?.color?.ifBlank { ai.clean("color") } ?: ai.clean("color"),
            sku = base?.sku?.ifBlank { ai.clean("sku") } ?: ai.clean("sku"),
            images = allImages,
            extractionSource = ProductExtractionSource.AI_ENRICHED,
        )
    }

    private fun buildProductPrompt(
        url: String,
        localContext: String,
        webFetchAvailable: Boolean,
    ): String = """
        Identify the primary sellable product from the verified page data below.
        URL: $url

        ${localContext.ifBlank {
            if (webFetchAvailable) "Use the web fetch tool for the URL." else "No verified page content is available. Return empty fields rather than guessing."
        }}

        Return this JSON shape only:
        {
          "source_url": "final canonical product URL or empty string",
          "title": "product title or empty string",
          "description": "short factual product description or empty string",
          "brand": "brand or empty string",
          "price": "numeric/display price without guessing or empty string",
          "currency": "currency code/symbol or empty string",
          "color": "selected/displayed color or empty string",
          "sku": "SKU/product ID or empty string",
          "image_url": "direct primary product image URL or empty string",
          "images": ["other direct product image URLs"]
        }
        Prefer the actual garment/product photography, not logos, icons, recommendation cards, banners, or unrelated products.
        If a field cannot be verified from the supplied page data, return an empty value. Never guess.
    """.trimIndent()

    private fun messageText(message: JSONObject): String = when (val content = message.opt("content")) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
        else -> content?.toString().orEmpty()
    }.trim()

    private fun extractJsonObject(value: String): JSONObject? {
        val cleaned = value
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        runCatching { JSONObject(cleaned) }.getOrNull()?.let { return it }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }

    private fun extractApiMessage(body: String): String = runCatching {
        val root = JSONObject(body)
        root.optJSONObject("error")?.optString("message")
            ?.takeIf(String::isNotBlank)
            ?: root.optString("message").takeIf(String::isNotBlank)
    }.getOrNull().orEmpty().take(300)

    private fun JSONObject.clean(key: String): String =
        optString(key).replace(Regex("\\s+"), " ").trim().take(MAX_FIELD_CHARS)

    private fun resolveEndpoint(baseUrl: String, endpoint: String): String {
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) return endpoint
        return "${baseUrl.trimEnd('/')}/${endpoint.trimStart('/')}"
    }

    private fun isOpenRouter(config: CustomAiConfig): Boolean =
        config.baseUrl.contains("openrouter.ai", ignoreCase = true)

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun io.ktor.client.request.HttpRequestBuilder.applyOpenRouterHeaders() {
        header("HTTP-Referer", "https://almi.ai")
        header("X-Title", "ALMI_AI")
    }

    companion object {
        private const val FREE_ANALYSIS_MODEL = "openrouter/free"
        private const val MAX_LOCAL_CONTEXT_CHARS = 18_000
        private const val MAX_WEB_FETCH_TOKENS = 16_000
        private const val MAX_FIELD_CHARS = 2_000
        private const val MAX_AI_IMAGES = 16
    }
}
