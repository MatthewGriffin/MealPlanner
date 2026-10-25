package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Opt-in live smoke test for ASDA's configured Algolia product search.
 *
 * Requires ASDA_ALGOLIA_API_KEY in the test process environment (or the supported
 * system property). The key is never printed. Ordinary test runs skip this test.
 *
 * Run with:
 * ASDA_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*AsdaLiveSmokeTest'
 */
class AsdaLiveSmokeTest {
    @Test
    fun configuredAlgoliaSearchReturnsParsedPricedProducts() = runBlocking {
        assumeTrue(
            "Set ASDA_LIVE_SMOKE_TEST=true to run the ASDA live smoke test.",
            System.getenv("ASDA_LIVE_SMOKE_TEST").equals("true", ignoreCase = true)
        )

        val httpClient = HttpClient(OkHttp)
        try {
            val client = AsdaSourceClient(httpClient)
            val result = AsdaRetailerAdapter(client).searchProducts("milk")
            val products = when (result) {
                is RetailProductSearchResult.Success -> result.productsWithOffers
                is RetailProductSearchResult.PartialSuccess -> result.productsWithOffers
                is RetailProductSearchResult.Unavailable -> fail(
                    "ASDA live search failed: ${result.errorCode}: ${result.reason}"
                )
            }

            assertTrue(products.isNotEmpty(), "ASDA returned no parsed priced products")
            assertTrue(products.all { it.product.retailerId == RetailerId.ASDA })
            assertTrue(products.all { it.offer.retailerId == RetailerId.ASDA })
            assertTrue(products.all { it.offer.price.minorUnits > 0L }, "ASDA returned a non-positive price")
            assertTrue(products.all { it.offer.sourceType == RetailOfferSourceType.LIVE })
            assertEquals(1, products.map { it.offer.price.currency }.distinct().size)
            assertEquals("GBP", products.first().offer.price.currency)
            println("ASDA live smoke test: parsedPricedProducts=${products.size}")
        } finally {
            httpClient.close()
        }
    }
}
