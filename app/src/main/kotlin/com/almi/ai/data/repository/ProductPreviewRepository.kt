package com.almi.ai.data.repository

import com.almi.ai.data.model.ProductPreview
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Product-link use case.
 *
 * Fast path: deterministic JSON-LD/OpenGraph/HTML extraction on-device.
 * Recovery path: the active AI route reads/enriches the URL when the page is blocked or the
 * deterministic result is too weak. A valid local result is never discarded just because the
 * optional AI enrichment fails.
 */
class ProductPreviewRepository @Inject constructor(
    private val pageExtractor: ProductPageExtractor,
    private val aiGateway: ProductAiGateway,
) {
    suspend fun load(url: String): Result<ProductPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = normalizeProductUrl(url)
            val local = pageExtractor.extract(normalized).getOrNull()

            if (local != null && isStrongEnough(local)) {
                return@runCatching local.preview
            }

            val ai = aiGateway.enrich(normalized, local).getOrNull()
            when {
                ai != null && ai.imageUrl != null -> ai
                local != null -> local.preview
                ai != null -> ai
                else -> throw IllegalStateException("product_extraction_failed")
            }
        }
    }

    private fun isStrongEnough(snapshot: ProductPageSnapshot): Boolean {
        val preview = snapshot.preview
        val titleIsUseful = preview.title.isNotBlank() &&
            !preview.title.equals("Product", ignoreCase = true) &&
            !preview.title.equals(preview.merchant, ignoreCase = true)
        return snapshot.confidence >= DIRECT_ACCEPT_CONFIDENCE &&
            titleIsUseful &&
            preview.imageUrl != null
    }

    companion object {
        private const val DIRECT_ACCEPT_CONFIDENCE = 0.72f
    }
}
