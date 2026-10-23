package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

class IcelandLiveSmokeTest {

    @Test
    fun optionalLiveSmokeTest_executesOnlyWhenExplicitlyEnabled() = runBlocking {
        val liveEnabled = System.getenv("ICELAND_LIVE_TESTS") == "true"
        if (!liveEnabled) {
            println("Skipping IcelandLiveSmokeTest because ICELAND_LIVE_TESTS is not set to 'true'.")
            return@runBlocking
        }

        val httpClient = HttpClient(OkHttp)
        val client = IcelandSourceClient(httpClient)
        val adapter = IcelandRetailerAdapter(client)

        val result = adapter.searchProducts("broccoli")
        assertTrue(
            result is RetailProductSearchResult.Success || result is RetailProductSearchResult.PartialSuccess,
            "Iceland live search did not return products: $result"
        )
    }

    @Test
    fun optionalEmptyQueryCatalogueSmokeTest_checksPaginationAndProductParsing() = runBlocking {
        val liveEnabled = System.getenv("ICELAND_LIVE_TESTS") == "true"
        if (!liveEnabled) {
            println("Skipping Iceland empty-query catalogue smoke test because ICELAND_LIVE_TESTS is not set to 'true'.")
            return@runBlocking
        }

        val httpClient = HttpClient(OkHttp)
        try {
            val client = IcelandSourceClient(httpClient)
            val parser = IcelandProductParser()
            val firstPage = client.fetchAlgoliaCataloguePageJson(page = 0, hitsPerPage = 100)
            if (firstPage == null) {
                val homepage = runCatching { client.fetchSearchHtml("broccoli") }
                val htmlDiagnostics = homepage.fold(
                    onSuccess = { html ->
                        val scripts = Regex("""(?i)<script[^>]+src=["']([^"']+)["']""")
                            .findAll(html).map { it.groupValues[1] }.distinct().take(30).joinToString()
                        val jsonLdCount = Regex("""(?i)<script[^>]*type=["']application/ld\+json["']""")
                            .findAll(html).count()
                        val algoliaMentions = Regex("""(?i).{0,50}(?:algolia|applicationId|apiKey|indexName).{0,80}""")
                            .findAll(html).take(8).joinToString(" | ") { it.value.replace(Regex("\\s+"), " ") }
                        "HTML succeeded (${html.length} chars); JSON-LD scripts=$jsonLdCount; script URLs=$scripts; config snippets=$algoliaMentions"
                    },
                    onFailure = { "HTML fallback failed: ${it::class.simpleName}: ${it.message}" }
                )
                error("Iceland Algolia catalogue request returned no response. $htmlDiagnostics")
            }

            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(firstPage!!).jsonObject
            val totalPages = root["nbPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val hits = root["hits"]?.jsonArray.orEmpty()
            val parsedProducts = parser.parseAlgoliaResponse(firstPage)

            assertTrue(totalPages > 0, "Empty-query response must expose positive nbPages")
            assertTrue(hits.isNotEmpty(), "Empty-query first page must contain products")
            assertTrue(parsedProducts.isNotEmpty(), "Empty-query first page must parse into priced products")

            if (totalPages > 1) {
                val secondPage = client.fetchAlgoliaCataloguePageJson(page = 1, hitsPerPage = 100)
                assertTrue(secondPage != null, "Iceland's public Algolia page 1 request returned no response")
                val secondRoot = json.parseToJsonElement(secondPage!!).jsonObject
                assertEquals(1, secondRoot["page"]?.jsonPrimitive?.content?.toIntOrNull())
                assertTrue(secondRoot["hits"]?.jsonArray?.isNotEmpty() == true)
            }

            println("Iceland empty-query smoke test: pages=" + totalPages + ", firstPageHits=" + hits.size + ", parsedProducts=" + parsedProducts.size)
        } finally {
            httpClient.close()
        }
    }

}
