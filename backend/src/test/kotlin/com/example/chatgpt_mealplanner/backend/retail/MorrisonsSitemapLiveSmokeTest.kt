package com.example.chatgpt_mealplanner.backend.retail

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in live smoke test for Morrisons' public sitemap and category listings.
 *
 * Run with:
 * MORRISONS_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*MorrisonsSitemapLiveSmokeTest'
 *
 * It is skipped by default so ordinary unit tests never depend on the public website.
 */
class MorrisonsSitemapLiveSmokeTest {
    @Test
    fun publicSitemapAndCategoryListingExposeProductData() = runBlocking {
        if (!System.getenv("MORRISONS_LIVE_SMOKE_TEST").equals("true", ignoreCase = true)) {
            println("Skipping live Morrisons smoke test; set MORRISONS_LIVE_SMOKE_TEST=true to enable.")
            return@runBlocking
        }

        val client = HttpClient(OkHttp)
        try {
            suspend fun fetch(url: String): Pair<Int, String> {
                val response = client.get(url) {
                    header(HttpHeaders.UserAgent, "PantryAssistantRetailSearch/1.0 (https://github.com/MatthewGriffin/MealPlanner)")
                    header(HttpHeaders.Accept, "application/xml,text/xml,text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                }
                return response.status.value to response.bodyAsText()
            }

            fun locations(xml: String): List<String> =
                Regex("(?is)<loc>\\s*(.*?)\\s*</loc>")
                    .findAll(xml)
                    .map { it.groupValues[1].trim() }
                    .toList()

            val indexUrl = "https://groceries.morrisons.com/sitemaps/sitemap_index.xml"
            val (indexStatus, indexBody) = fetch(indexUrl)
            assertTrue(indexStatus in 200..299, "Morrisons sitemap index returned HTTP $indexStatus")
            assertTrue(
                indexBody.contains("<sitemapindex", ignoreCase = true),
                "Morrisons sitemap response was not an XML sitemap index. Body prefix: ${indexBody.take(300)}"
            )

            val sitemapUrls = locations(indexBody)
            assertTrue(sitemapUrls.isNotEmpty(), "Morrisons sitemap index contained no <loc> entries")
            val productSitemapUrls = sitemapUrls.filter { it.contains("product", ignoreCase = true) }
            assertTrue(
                productSitemapUrls.isNotEmpty(),
                "No product sitemap was identifiable in index entries: ${sitemapUrls.take(20)}"
            )
            println("MORRISONS_SMOKE sitemap_index_status=$indexStatus sitemap_count=${sitemapUrls.size}")

            val sitemapUrl = productSitemapUrls.first()
            val (sitemapStatus, sitemapBody) = fetch(sitemapUrl)
            assertTrue(sitemapStatus in 200..299, "Morrisons product sitemap returned HTTP $sitemapStatus")
            val productUrls = locations(sitemapBody).filter { it.contains("/products/", ignoreCase = true) }
            assertTrue(productUrls.isNotEmpty(), "Morrisons product sitemap contained no product URLs")
            println("MORRISONS_SMOKE product_sitemap_status=$sitemapStatus product_url_count=${productUrls.size}")

            // Product detail pages can return 403 even when the public catalogue/sitemap is readable.
            // Test category listing pages independently: they may contain product cards with names,
            // prices and stable IDs without requiring a request to each product detail page.
            val categoryUrl = "https://groceries.morrisons.com/categories/fresh-foods/milk-eggs-butter/fresh-milk/semi-skimmed-milk/177269"
            val (categoryStatus, categoryBody) = fetch(categoryUrl)
            assertTrue(categoryStatus in 200..299, "Morrisons category page returned HTTP $categoryStatus")
            assertTrue(categoryBody.length > 1000, "Morrisons category page body was unexpectedly short")

            val listedProductUrls = Regex("https?://groceries\\.morrisons\\.com/products/[^\\\"'\\s<>]+")
                .findAll(categoryBody)
                .map { it.value.substringBefore('?').trimEnd('/') }
                .distinct()
                .toList()
            val productIds = Regex("(?i)(?:/products/[^/\\\"'<>]+/|product[_-]?id[\\\"'= :]+)(\\d{6,})")
                .findAll(categoryBody)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            val priceMentions = Regex("(?i)(?:£\\s?\\d+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?p\\b)")
                .findAll(categoryBody)
                .count()
            val hasProductStructuredData = categoryBody.contains("schema.org/Product", ignoreCase = true) ||
                Regex("(?is)\\\"@type\\\"\\s*:\\s*\\\"Product\\\"").containsMatchIn(categoryBody)

            println(
                "MORRISONS_SMOKE category_status=$categoryStatus body_chars=${categoryBody.length} " +
                    "product_urls=${listedProductUrls.size} product_ids=${productIds.size} " +
                    "price_mentions=$priceMentions structured_product_data=$hasProductStructuredData"
            )

            // Isolate the visible card for the first product title and inspect only likely price markup.
            val titleMarker = """data-test="fop-title""""
            val titlePosition = categoryBody.indexOf(titleMarker, ignoreCase = true)
            if (titlePosition >= 0) {
                val cardMarker = Regex("""(?is)<div\b[^>]*class=["'][^"']*product-card-container[^"']*["'][^>]*>""")
                val cardStart = cardMarker.findAll(categoryBody.substring(0, titlePosition))
                    .lastOrNull()?.range?.first
                if (cardStart != null) {
                    val nextCard = cardMarker.find(categoryBody, titlePosition + titleMarker.length)
                    val cardEnd = (nextCard?.range?.first ?: (titlePosition + 12000))
                        .coerceAtMost(categoryBody.length)
                    val card = categoryBody.substring(cardStart, cardEnd)
                    val priceAttributeTags = Regex("""(?is)<[^>]*(?:price|amount|currency|cost)[^>]*>""")
                        .findAll(card)
                        .map { it.value.replace(Regex("""\s+"""), " ").take(260) }
                        .distinct()
                        .take(12)
                        .toList()
                    println("MORRISONS_SMOKE first_card_chars=${card.length} title_position_in_card=${titlePosition - cardStart}")
                    println("MORRISONS_SMOKE first_card_price_attribute_tags=$priceAttributeTags")

                    val poundSnippets = Regex("""£\s?\d+(?:\.\d{1,2})?""")
                        .findAll(card)
                        .map { match ->
                            val from = (match.range.first - 180).coerceAtLeast(0)
                            val to = (match.range.last + 181).coerceAtMost(card.length)
                            card.substring(from, to).replace(Regex("""\s+"""), " ").take(420)
                        }
                        .distinct()
                        .take(8)
                        .toList()
                    println("MORRISONS_SMOKE first_card_pound_snippets=$poundSnippets")
                    println("MORRISONS_SMOKE first_card_text_snippet=" +
                        card.replace(Regex("""<script\b[^>]*>.*?</script>"""), " ")
                            .replace(Regex("""<style\b[^>]*>.*?</style>"""), " ")
                            .replace(Regex("""<[^>]+>"""), " ")
                            .replace(Regex("""\s+"""), " ")
                            .take(1200))
                } else {
                    println("MORRISONS_SMOKE product_title_found_but_card_container_not_found")
                }
            } else {
                println("MORRISONS_SMOKE product_title_marker_not_found")
            }
            val jsonLdBlocks = Regex("""(?is)<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""")
                .findAll(categoryBody)
                .map { it.groupValues[1].trim().replace(Regex("""\s+"""), " ").take(1000) }
                .toList()
            println("MORRISONS_SMOKE json_ld_blocks=${jsonLdBlocks.size}")
            jsonLdBlocks.forEachIndexed { index, block ->
                println("MORRISONS_SMOKE json_ld_${index + 1}=${block.take(1000)}")
            }

            // Print bounded excerpts around fields likely to contain the actual card data.
            listOf("productName", "unitPrice", "product-card-price", "pricePerUnit", "currentPrice").forEach { token ->
                val position = categoryBody.indexOf(token, ignoreCase = true)
                if (position >= 0) {
                    val start = (position - 350).coerceAtLeast(0)
                    val end = (position + 900).coerceAtMost(categoryBody.length)
                    println("MORRISONS_SMOKE field_excerpt_$token=${categoryBody.substring(start, end).replace(Regex("""\\s+"""), " ").take(1200)}")
                } else {
                    println("MORRISONS_SMOKE field_excerpt_$token=NOT_FOUND")
                }
            }

            val dataMarkers = listOf("data-test=", "data-testid=", "product-listing-structured-data", "product-card", "productCard", "productName", "product_name", "unitPrice", "price")
                .associateWith { marker -> Regex(Regex.escape(marker), RegexOption.IGNORE_CASE).findAll(categoryBody).count() }
            println("MORRISONS_SMOKE data_marker_counts=$dataMarkers")
            println("MORRISONS_SMOKE first_product_urls=${listedProductUrls.take(5)}")

            assertTrue(
                listedProductUrls.isNotEmpty() || productIds.isNotEmpty() || hasProductStructuredData,
                "Category page loaded but no product cards/IDs/structured product data were detectable. " +
                    "Body prefix: ${categoryBody.take(500)}"
            )
            assertTrue(
                priceMentions > 0,
                "Category page exposed product-like data but no recognizable prices. " +
                    "Body prefix: ${categoryBody.take(500)}"
            )
        } finally {
            client.close()
        }
    }
}
