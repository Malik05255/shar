package com.almi.ai.data.repository

import com.almi.ai.data.model.ProductExtractionSource
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductPageExtractorTest {
    private val extractor = ProductPageExtractor()

    @Test
    fun extractsSchemaOrgProductBeforeGenericPageMetadata() {
        val html = """
            <html>
              <head>
                <link rel="canonical" href="/products/nasa-set" />
                <meta property="og:title" content="Generic Store Page" />
                <meta property="og:image" content="/assets/store-banner.jpg" />
                <script type="application/ld+json">
                {
                  "@context": "https://schema.org",
                  "@graph": [
                    {
                      "@type": "Product",
                      "name": "NASA T-Shirt and Shorts Set",
                      "description": "White NASA shirt with blue shorts",
                      "brand": {"@type": "Brand", "name": "NASA Style"},
                      "sku": "NASA-SET-42",
                      "color": "White / Blue",
                      "image": [
                        "https://shop.example.com/images/nasa-main.jpg",
                        "https://shop.example.com/images/nasa-side.jpg"
                      ],
                      "offers": {
                        "@type": "Offer",
                        "price": "129.00",
                        "priceCurrency": "SAR"
                      }
                    }
                  ]
                }
                </script>
              </head>
              <body><h1>Fallback title</h1></body>
            </html>
        """.trimIndent()

        val document = Jsoup.parse(html, "https://shop.example.com/item?id=42")
        val snapshot = extractor.extractDocument(document, "https://shop.example.com/item?id=42")
        val product = snapshot.preview

        assertEquals("NASA T-Shirt and Shorts Set", product.title)
        assertEquals("NASA Style", product.brand)
        assertEquals("129.00", product.price)
        assertEquals("SAR", product.currency)
        assertEquals("White / Blue", product.color)
        assertEquals("NASA-SET-42", product.sku)
        assertEquals("https://shop.example.com/images/nasa-main.jpg", product.imageUrl)
        assertEquals("https://shop.example.com/images/nasa-main.jpg", product.images.first())
        assertTrue(product.images.contains("https://shop.example.com/images/nasa-side.jpg"))
        assertEquals("https://shop.example.com/products/nasa-set", product.sourceUrl)
        assertEquals(ProductExtractionSource.STRUCTURED_DATA, product.extractionSource)
        assertTrue(snapshot.confidence >= 0.72f)
    }

    @Test
    fun fallsBackToOpenGraphAndLazyLoadedImages() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="Blue Oversized Shirt" />
                <meta property="og:description" content="Cotton oversized shirt" />
                <meta property="product:price:amount" content="49.95" />
                <meta property="product:price:currency" content="USD" />
              </head>
              <body>
                <img src="/assets/logo.svg" />
                <img data-zoom-image="/products/blue-shirt-zoom.webp" src="/products/blue-shirt-small.jpg" />
              </body>
            </html>
        """.trimIndent()

        val document = Jsoup.parse(html, "https://fashion.example/product/blue-shirt")
        val product = extractor.extractDocument(document, "https://fashion.example/product/blue-shirt").preview

        assertEquals("Blue Oversized Shirt", product.title)
        assertEquals("49.95", product.price)
        assertEquals("USD", product.currency)
        assertEquals("https://fashion.example/products/blue-shirt-zoom.webp", product.imageUrl)
        assertEquals(ProductExtractionSource.HTML, product.extractionSource)
    }

    @Test
    fun rejectsPrivateAndLocalProductUrls() {
        val blocked = listOf(
            "http://localhost/product",
            "http://127.0.0.1/product",
            "http://10.0.0.2/product",
            "http://172.16.2.5/product",
            "http://192.168.1.5/product",
            "http://169.254.1.5/product",
        )

        blocked.forEach { url ->
            val result = runCatching { normalizeProductUrl(url) }
            assertTrue("Expected URL to be blocked: $url", result.isFailure)
        }
    }

    @Test
    fun normalizesPublicUrlWithoutScheme() {
        assertEquals(
            "https://shop.example.com/products/1",
            normalizeProductUrl("shop.example.com/products/1"),
        )
    }
}
