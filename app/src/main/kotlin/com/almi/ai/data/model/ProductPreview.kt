package com.almi.ai.data.model

data class ProductPreview(
    val sourceUrl: String,
    val title: String,
    val imageUrl: String?,
    val merchant: String,
    val description: String = "",
    val brand: String = "",
    val price: String = "",
    val currency: String = "",
    val color: String = "",
    val sku: String = "",
    val images: List<String> = imageUrl?.let(::listOf).orEmpty(),
    /** Retailer-exposed apparel size labels, normalized but still brand-specific. */
    val availableSizes: List<String> = emptyList(),
    val extractionSource: ProductExtractionSource = ProductExtractionSource.HTML,
) {
    val displayPrice: String
        get() = listOf(price, currency).filter(String::isNotBlank).joinToString(" ")
}

enum class ProductExtractionSource {
    STRUCTURED_DATA,
    HTML,
    AI_ENRICHED,
}
