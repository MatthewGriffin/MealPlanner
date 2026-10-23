package com.example.chatgpt_mealplanner.backend.retail

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in live smoke test for Morrisons' public sitemap and product pages.
 *
 * Run with:
 * MORRISONS_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*MorrisonsSitemapLiveSmokeTest' --info
 *
 * It is skipped by default so ordinary unit tests never depend on the public website.
 */
class MorrisonsSitemapLiveSmokeTest {
    @Test
    fun publicSitemapLeadsToAProductPageWithStructuredProductData() = runBlocking {
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
            println("MORRISONS_SMOKE product_sitemap_candidates=${productSitemapUrls.take(5)}")

            var productPageUrl: String? = null
            val inspectedSitemaps = mutableListOf<String>()
            val pending = ArrayDeque(productSitemapUrls.take(3))
            while (pending.isNotEmpty() && inspectedSitemaps.size < 5 && productPageUrl == null) {
                val sitemapUrl = pending.removeFirst()
                if (sitemapUrl in inspectedSitemaps) continue
                inspectedSitemaps += sitemapUrl
                val (status, body) = fetch(sitemapUrl)
                assertTrue(status in 200..299, "Morrisons product sitemap $sitemapUrl returned HTTP $status")
                val entries = locations(body)
                assertTrue(entries.isNotEmpty(), "Morrisons sitemap $sitemapUrl contained no <loc> entries")
                productPageUrl = entries.firstOrNull { it.contains("/products/", ignoreCase = true) }
                if (productPageUrl == null && body.contains("<sitemapindex", ignoreCase = true)) {
                    entries.filter { it.contains("product", ignoreCase = true) }
                        .take(3)
                        .forEach { if (it !in inspectedSitemaps) pending.addLast(it) }
                }
                println("MORRISONS_SMOKE sitemap=${sitemapUrl.substringAfterLast('/')} status=$status entries=${entries.size}")
            }

            assertTrue(
                productPageUrl != null,
                "Inspected product sitemap entries but found no /products/ URL. Sitemaps: $inspectedSitemaps"
            )
            val (productStatus, productBody) = fetch(productPageUrl!!)
            assertTrue(productStatus in 200..299, "Morrisons product page returned HTTP $productStatus: $productPageUrl")
            assertTrue(
                productBody.contains("schema.org/Product", ignoreCase = true) ||
                    Regex("(?is)\"@type\"\\s*:\\s*\"Product\"").containsMatchIn(productBody),
                "Morrisons product page had no recognizable Product structured data: $productPageUrl"
            )
            println("MORRISONS_SMOKE product_page_status=$productStatus url=$productPageUrl structured_product=true")
        } finally {
            client.close()
        }
    }
}
