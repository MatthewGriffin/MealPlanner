package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

class IcelandLiveSmokeTest {

    @Test
    fun optionalLiveSmokeTest_executesOnlyWhenExplicitlyEnabled() = runBlocking {
        if (System.getenv("ICELAND_LIVE_TESTS") != "true") {
            println("Skipping IcelandLiveSmokeTest because ICELAND_LIVE_TESTS is not set to 'true'.")
            return@runBlocking
        }

        val httpClient = HttpClient(OkHttp)
        try {
            val client = IcelandSourceClient(httpClient)
            val adapter = IcelandRetailerAdapter(client)
            val result = adapter.searchProducts("broccoli")
            assertTrue(
                result is RetailProductSearchResult.Success || result is RetailProductSearchResult.PartialSuccess,
                "Iceland live search did not return products: $result"
            )
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun optionalEmptyQueryCatalogueSmokeTest_crawlsEveryPageAndParsesProducts() = runBlocking {
        if (System.getenv("ICELAND_LIVE_TESTS") != "true") {
            println("Skipping Iceland empty-query catalogue smoke test because ICELAND_LIVE_TESTS is not set to 'true'.")
            return@runBlocking
        }

        val httpClient = HttpClient(OkHttp)
        try {
            val client = IcelandSourceClient(httpClient)
            val parser = IcelandProductParser()
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val allProductIds = LinkedHashSet<String>()
            var expectedPages: Int? = null
            var totalHits = 0
            var totalParsed = 0
            var page = 0

            while (expectedPages == null || page < expectedPages!!) {
                val raw = client.fetchAlgoliaCataloguePageJson(page = page, hitsPerPage = 100)
                    ?: error("Iceland returned no catalogue response for page $page")
                val root = json.parseToJsonElement(raw).jsonObject
                val responsePage = root["page"]?.jsonPrimitive?.content?.toIntOrNull()
                val totalPages = root["nbPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val hits = root["hits"]?.jsonArray.orEmpty()
                val parsed = parser.parseAlgoliaResponse(raw)

                assertEquals(page, responsePage, "Iceland response page does not match requested page")
                assertTrue(totalPages > 0, "Catalogue response must expose positive nbPages")
                assertTrue(page < totalPages, "Catalogue pagination ended before requested page $page")
                if (expectedPages == null) expectedPages = totalPages
                assertEquals(expectedPages, totalPages, "Iceland changed nbPages during the catalogue crawl")
                assertTrue(hits.isNotEmpty(), "Catalogue page $page contained no hits")
                assertTrue(parsed.isNotEmpty(), "Catalogue page $page contained no parseable priced products")

                totalHits += hits.size
                totalParsed += parsed.size
                parsed.forEach { allProductIds += it.productWithOffer.product.retailerProductId }
                page++
            }

            assertEquals(expectedPages, page, "Not all Iceland catalogue pages were fetched")
            assertTrue(allProductIds.size >= 100, "Full catalogue crawl produced suspiciously few unique products")
            println(
                "Iceland full catalogue smoke test: pages=$page, rawHits=$totalHits, " +
                    "parsedOffers=$totalParsed, uniqueProductIds=${allProductIds.size}"
            )
        } finally {
            httpClient.close()
        }
    }
}
