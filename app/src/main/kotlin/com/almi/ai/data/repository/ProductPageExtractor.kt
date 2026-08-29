package com.almi.ai.data.repository

import com.almi.ai.data.model.ProductExtractionSource
import com.almi.ai.data.model.ProductPreview
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Fast, provider-free product extraction.
 *
 * Order of trust:
 * 1) schema.org Product JSON-LD
 * 2) OpenGraph / product meta tags
 * 3) semantic HTML / image fallbacks
 *
 * AI is intentionally not used here. [ProductPreviewRepository] may ask the active AI gateway
 * to enrich or recover the result when this deterministic pass is incomplete or blocked.
 */
class ProductPageExtractor @Inject constructor() {

    suspend fun extract(url: String): Result<ProductPageSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = normalizeProductUrl(url)
            val document = Jsoup.connect(normalizedUrl)
                .userAgent(MOBILE_USER_AGENT)
                .referrer("https://www.google.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .timeout(PAGE_TIMEOUT_MS)
                .maxBodySize(MAX_HTML_BYTES)
                .followRedirects(true)
                .get()

            extractDocument(document, normalizedUrl)
        }
    }

    internal fun extractDocument(document: Document, requestedUrl: String): ProductPageSnapshot {
        val finalUrl = document.location().ifBlank { requestedUrl }
        val finalUri = URI(finalUrl)
        val merchant = finalUri.host?.removePrefix("www.")?.substringBefore(':').orEmpty()
        val productJson = findProductJson(document)
        val hasStructuredProduct = productJson != null

        val canonicalUrl = firstNonBlank(
            document.selectFirst("link[rel=canonical]")?.attr("href"),
            productJson?.optString("url"),
            finalUrl,
        )?.let { resolveUrl(finalUri, it) } ?: finalUrl

        val title = firstNonBlank(
            productJson?.optString("name"),
            meta(document, "property", "og:title"),
            meta(document, "name", "twitter:title"),
            document.selectFirst("h1")?.text(),
            document.title(),
        ).orEmpty().cleanText().take(MAX_TITLE_CHARS).ifBlank { merchant.ifBlank { "Product" } }

        val description = firstNonBlank(
            productJson?.optString("description"),
            meta(document, "property", "og:description"),
            meta(document, "name", "description"),
            meta(document, "name", "twitter:description"),
        ).orEmpty().cleanText().take(MAX_DESCRIPTION_CHARS)

        val brand = firstNonBlank(
            jsonText(productJson?.opt("brand"), "name", "brand"),
            meta(document, "property", "product:brand"),
            semanticValue(document.selectFirst("[itemprop=brand]")),
        ).orEmpty().cleanText().take(120)

        val offers = firstOffer(productJson?.opt("offers"))
        val price = firstNonBlank(
            offers?.optString("price"),
            offers?.optString("lowPrice"),
            meta(document, "property", "product:price:amount"),
            semanticValue(document.selectFirst("[itemprop=price]")),
        ).orEmpty().cleanText().take(60)

        val currency = firstNonBlank(
            offers?.optString("priceCurrency"),
            meta(document, "property", "product:price:currency"),
            semanticValue(document.selectFirst("[itemprop=priceCurrency]")),
        ).orEmpty().cleanText().take(16)

        val color = firstNonBlank(
            productJson?.optString("color"),
            semanticValue(document.selectFirst("[itemprop=color]")),
        ).orEmpty().cleanText().take(100)

        val sku = firstNonBlank(
            productJson?.optString("sku"),
            productJson?.optString("mpn"),
            semanticValue(document.selectFirst("[itemprop=sku]")),
        ).orEmpty().cleanText().take(100)

        val images = linkedSetOf<String>()
        imageValues(productJson?.opt("image")).forEach { raw -> addResolvedImage(images, finalUri, raw) }
        listOfNotNull(
            meta(document, "property", "og:image:secure_url"),
            meta(document, "property", "og:image"),
            meta(document, "name", "twitter:image"),
            document.selectFirst("link[rel=image_src]")?.attr("href"),
        ).forEach { addResolvedImage(images, finalUri, it) }

        document.select("img").take(MAX_IMAGE_ELEMENTS).forEach { image ->
            listOf(
                image.attr("data-zoom-image"),
                image.attr("data-original"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("src"),
                bestSrcSetCandidate(image.attr("srcset")),
                bestSrcSetCandidate(image.attr("data-srcset")),
            ).forEach { addResolvedImage(images, finalUri, it) }
        }

        val imageList = images.take(MAX_IMAGE_CANDIDATES)
        val imageUrl = imageList.firstOrNull()
        val source = if (hasStructuredProduct) ProductExtractionSource.STRUCTURED_DATA else ProductExtractionSource.HTML

        var confidence = 0.10f
        if (hasStructuredProduct) confidence += 0.25f
        if (title.isNotBlank() && title != merchant && !title.equals("Product", ignoreCase = true)) confidence += 0.25f
        if (imageUrl != null) confidence += 0.28f
        if (description.isNotBlank()) confidence += 0.04f
        if (brand.isNotBlank()) confidence += 0.04f
        if (price.isNotBlank()) confidence += 0.04f

        val readableText = document.body()?.text().orEmpty()
            .cleanText()
            .take(MAX_READABLE_TEXT_CHARS)

        return ProductPageSnapshot(
            preview = ProductPreview(
                sourceUrl = canonicalUrl,
                title = title,
                imageUrl = imageUrl,
                merchant = merchant,
                description = description,
                brand = brand,
                price = price,
                currency = currency,
                color = color,
                sku = sku,
                images = imageList,
                extractionSource = source,
            ),
            confidence = confidence.coerceIn(0f, 1f),
            readableText = readableText,
        )
    }

    private fun findProductJson(document: Document): JSONObject? {
        document.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }.trim()
            if (raw.isBlank()) return@forEach
            val parsed: Any = runCatching { JSONObject(raw) }.getOrElse {
                runCatching { JSONArray(raw) }.getOrNull() ?: return@forEach
            }
            findProductNode(parsed)?.let { return it }
        }
        return null
    }

    private fun findProductNode(value: Any?, depth: Int = 0): JSONObject? {
        if (value == null || depth > MAX_JSON_DEPTH) return null
        return when (value) {
            is JSONObject -> {
                if (jsonTypeContains(value.opt("@type"), "Product")) return value

                val graph = value.opt("@graph")
                findProductNode(graph, depth + 1)?.let { return it }

                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        findProductNode(child, depth + 1)?.let { return it }
                    }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findProductNode(value.opt(index), depth + 1)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun jsonTypeContains(value: Any?, expected: String): Boolean = when (value) {
        is String -> value.equals(expected, ignoreCase = true) || value.endsWith("/$expected", ignoreCase = true)
        is JSONArray -> (0 until value.length()).any { index -> jsonTypeContains(value.opt(index), expected) }
        else -> false
    }

    private fun firstOffer(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { value.optJSONObject(it) }
        else -> null
    }

    private fun imageValues(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is JSONObject -> listOfNotNull(
            value.optString("url").takeIf(String::isNotBlank),
            value.optString("contentUrl").takeIf(String::isNotBlank),
        )
        is JSONArray -> buildList {
            for (index in 0 until value.length()) addAll(imageValues(value.opt(index)))
        }
        else -> emptyList()
    }

    private fun jsonText(value: Any?, vararg preferredKeys: String): String? = when (value) {
        is String -> value
        is JSONObject -> preferredKeys.firstNotNullOfOrNull { key -> value.optString(key).takeIf(String::isNotBlank) }
        else -> null
    }

    private fun semanticValue(element: Element?): String? {
        if (element == null) return null
        return firstNonBlank(element.attr("content"), element.attr("value"), element.text())
    }

    private fun meta(document: Document, attribute: String, value: String): String? =
        document.selectFirst("meta[$attribute=$value]")?.attr("content")?.takeIf(String::isNotBlank)

    private fun addResolvedImage(target: MutableSet<String>, baseUri: URI, raw: String?) {
        if (raw.isNullOrBlank()) return
        val resolved = resolveUrl(baseUri, raw) ?: return
        if (!isUsableImageUrl(resolved)) return
        target += resolved
    }

    private fun bestSrcSetCandidate(srcSet: String): String? {
        if (srcSet.isBlank()) return null
        return srcSet.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .maxByOrNull { candidate ->
                candidate.substringAfterLast(' ', "0")
                    .removeSuffix("w")
                    .removeSuffix("x")
                    .toDoubleOrNull() ?: 0.0
            }
            ?.substringBefore(' ')
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun isUsableImageUrl(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return (url.startsWith("https://") || url.startsWith("http://")) &&
            !lower.endsWith(".svg") &&
            !lower.endsWith(".gif") &&
            !lower.contains("favicon") &&
            !lower.contains("sprite") &&
            !lower.contains("logo")
    }

    private fun resolveUrl(baseUri: URI, value: String): String? = runCatching {
        val cleaned = value.trim().replace("&amp;", "&")
        baseUri.resolve(cleaned).toString().takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
    }.getOrNull()

    companion object {
        private const val PAGE_TIMEOUT_MS = 20_000
        private const val MAX_HTML_BYTES = 5 * 1024 * 1024
        private const val MAX_TITLE_CHARS = 220
        private const val MAX_DESCRIPTION_CHARS = 1_500
        private const val MAX_READABLE_TEXT_CHARS = 18_000
        private const val MAX_IMAGE_ELEMENTS = 120
        private const val MAX_IMAGE_CANDIDATES = 16
        private const val MAX_JSON_DEPTH = 7
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}

data class ProductPageSnapshot(
    val preview: ProductPreview,
    val confidence: Float,
    val readableText: String,
)

internal fun normalizeProductUrl(input: String): String {
    val trimmed = input.trim()
    require(trimmed.isNotBlank()) { "empty_product_url" }
    val candidate = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    val uri = URI(candidate)
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "unsupported_product_scheme" }
    require(!uri.host.isNullOrBlank()) { "invalid_product_host" }
    require(!isLocalOrPrivateHost(uri.host.orEmpty())) { "private_product_host_not_allowed" }
    return uri.toString()
}

private fun isLocalOrPrivateHost(host: String): Boolean {
    val normalized = host.lowercase().trim('[', ']')
    if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
    if (normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")) return true

    val parts = normalized.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return false
    val a = parts[0].toInt()
    val b = parts[1].toInt()
    return a == 10 ||
        a == 127 ||
        (a == 169 && b == 254) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        a == 0
}

private fun String.cleanText(): String = replace(Regex("\\s+"), " ").trim()

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }
