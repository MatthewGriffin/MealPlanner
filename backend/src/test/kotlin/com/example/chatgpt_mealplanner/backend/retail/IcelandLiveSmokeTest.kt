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
        assertTrue(result is RetailProductSearchResult.Success || result is RetailProductSearchResult.PartialSuccess)
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
            assertTrue(firstPage != null, "Iceland's public Algolia catalogue request returned no response")

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

            println("Iceland empty-query smoke test: pages=\u0024totalPages, firstPageHits=\u0024{hits.size}, parsedProducts=\u0024{parsedProducts.size}")
        } finally {
            httpClient.close()
        }
    }

}
