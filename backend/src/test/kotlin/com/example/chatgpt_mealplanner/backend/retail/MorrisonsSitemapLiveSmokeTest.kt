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

            // Inspect every sitemap referenced by the index to determine whether category
            // URLs are also published, rather than assuming the first product sitemap is enough.
            val sitemapContents = sitemapUrls.map { url ->
                val (status, body) = fetch(url)
                val entries = if (status in 200..299) locations(body) else emptyList()
                val productEntries = entries.filter { it.contains("/products/", ignoreCase = true) }
                val categoryEntries = entries.filter { it.contains("/categories/", ignoreCase = true) }
                println(
                    "MORRISONS_SMOKE sitemap status=$status url=$url entries=${entries.size} " +
                        "product_urls=${productEntries.size} category_urls=${categoryEntries.size} " +
                        "sample_entries=${entries.take(3)}"
                )
                Triple(url, status, entries)
            }
            val productSitemap = sitemapContents.firstOrNull {
                it.first.contains("product", ignoreCase = true) && it.second in 200..299
            }
            assertTrue(productSitemap != null, "No product sitemap from the index could be fetched successfully")
            val productUrls = productSitemap!!.third.filter { it.contains("/products/", ignoreCase = true) }
            assertTrue(productUrls.isNotEmpty(), "Morrisons product sitemap contained no product URLs")
            println(
                "MORRISONS_SMOKE product_sitemap_status=${productSitemap.second} " +
                    "product_url_count=${productUrls.size}"
            )

            // Sample category sitemap entries to check whether different categories expose
            // crawlable product listings, rather than assuming the milk category is representative.
            val categorySitemap = sitemapContents.firstOrNull {
                it.first.contains("categories", ignoreCase = true) && it.second in 200..299
            }
            val categoryUrls = categorySitemap?.third.orEmpty().filter {
                it.contains("/categories/", ignoreCase = true) && !it.contains("/promotions/", ignoreCase = true)
            }.distinct()
            println("MORRISONS_SMOKE category_sitemap_urls=${categoryUrls.size}")
            categoryUrls.take(8).forEachIndexed { index, url ->
                val (status, body) = fetch(url)
                val cardCount = Regex("""product-card-container""").findAll(body).count()
                val totalProducts = Regex("""(?i)["']totalProducts["']\\s*:\\s*(\\d+)""")
                    .findAll(body).map { it.groupValues[1].toInt() }.distinct().toList()
                val paginationFields = listOf("currentPage", "pageNumber", "pageSize", "pageCount", "hasNextPage", "nextPage", "isNextPageFetching")
                    .associateWith { field ->
                        Regex("""(?i)["']${Regex.escape(field)}["']\\s*:\\s*(?:"([^"]*)"|(true|false|\\d+))""")
                            .findAll(body).map { it.groupValues.drop(1).firstOrNull { value -> value.isNotEmpty() }.orEmpty() }
                            .distinct().take(4).toList()
                    }.filterValues { it.isNotEmpty() }
                println(
                    "MORRISONS_SMOKE category_sample_${index + 1} status=$status url=$url " +
                        "body_chars=${body.length} product_card_markers=$cardCount " +
                        "total_products_values=$totalProducts pagination_fields=$paginationFields"
                )
            }
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

            // Parse visible product cards to verify name, ID, shelf price and unit price together.
            val cardMarker = Regex("""(?is)<div\b[^>]*class=["'][^"']*product-card-container[^"']*["'][^>]*>""")
            val cardStarts = cardMarker.findAll(categoryBody).map { it.range.first }.toList()
            val titleRegex = Regex("""(?is)<[^>]*data-test=["']fop-title["'][^>]*>(.*?)</[^>]+>""")
            val priceRegex = Regex("""(?is)<[^>]*data-test=["']fop-price["'][^>]*>(.*?)</[^>]+>""")
            val unitPriceRegex = Regex("""(?is)<[^>]*data-test=["']fop-price-per-unit["'][^>]*>(.*?)</[^>]+>""")
            val productPathRegex = Regex("""(?i)/products/[^/""'<>]+/(\d{6,})""")
            fun visibleText(value: String): String = value
                .replace(Regex("""(?is)<[^>]+>"""), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace(Regex("""\s+"""), " ")
                .trim()

            val parsedCards = cardStarts.mapIndexedNotNull { index, cardStart ->
                val cardEnd = cardStarts.getOrNull(index + 1) ?: categoryBody.length
                val card = categoryBody.substring(cardStart, cardEnd)
                val name = titleRegex.find(card)?.groupValues?.get(1)?.let(::visibleText).orEmpty()
                val price = priceRegex.find(card)?.groupValues?.get(1)?.let(::visibleText).orEmpty()
                val unitPrice = unitPriceRegex.find(card)?.groupValues?.get(1)?.let(::visibleText).orEmpty()
                val productId = productPathRegex.find(card)?.groupValues?.get(1).orEmpty()
                if (name.isBlank() && price.isBlank() && productId.isBlank()) null
                else mapOf("name" to name, "id" to productId, "price" to price, "unitPrice" to unitPrice)
            }
            val completeCards = parsedCards.count {
                it["name"].orEmpty().isNotBlank() &&
                    it["id"].orEmpty().isNotBlank() &&
                    Regex("""£\s?\d+(?:\.\d{1,2})?""").containsMatchIn(it["price"].orEmpty())
            }
            println(
                "MORRISONS_SMOKE card_parse card_containers=${cardStarts.size} parsed_cards=${parsedCards.size} " +
                    "complete_name_id_price=$completeCards missing_name=${parsedCards.count { it["name"].orEmpty().isBlank() }} " +
                    "missing_id=${parsedCards.count { it["id"].orEmpty().isBlank() }} " +
                    "missing_price=${parsedCards.count { !Regex("""£\s?\d+(?:\.\d{1,2})?""").containsMatchIn(it["price"].orEmpty()) }}"
            )
            parsedCards.take(10).forEachIndexed { index, item ->
                println("MORRISONS_SMOKE parsed_card_${index + 1}=$item")
            }

            val paginationSnippets = Regex("""(?is)<[^>]*(?:rel=["']next["']|aria-label=["'][^"']*(?:next|page)[^"']*|data-test=["'][^"']*(?:pagination|load-more|next-page)[^"']*)[^>]*>""")
                .findAll(categoryBody)
                .map { it.value.replace(Regex("""\s+"""), " ").take(300) }
                .distinct()
                .take(15)
                .toList()
            val paginationMetadata = Regex("""(?i)(?:total[_A-Za-z-]*(?:record|product|page|result)|page[_A-Za-z-]*(?:size|number|count)|hasNextPage|nextPage|loadMore)""")
                .findAll(categoryBody)
                .map { match ->
                    val from = (match.range.first - 100).coerceAtLeast(0)
                    val to = (match.range.last + 180).coerceAtMost(categoryBody.length)
                    categoryBody.substring(from, to).replace(Regex("""\s+"""), " ").take(300)
                }
                .distinct()
                .take(12)
                .toList()
            println("MORRISONS_SMOKE pagination_elements=$paginationSnippets")
            println("MORRISONS_SMOKE pagination_metadata=$paginationMetadata")
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
