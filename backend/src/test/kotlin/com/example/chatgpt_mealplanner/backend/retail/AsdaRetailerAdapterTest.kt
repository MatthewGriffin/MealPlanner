package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AsdaRetailerAdapterTest {

    @Test
    fun capabilityAdvertisesLiveOnlineSearchButNotBranchAvailability() {
        val capability = AsdaRetailerAdapter().getCapability()

        assertEquals(RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE, capability.mode)
        assertTrue(capability.searchSupported)
        assertTrue(capability.livePriceSupported)
        assertFalse(capability.availabilitySupported)
        assertTrue(capability.promotionsSupported)
    }

    @Test
    fun searchReturnsParsedOnlineOffers() = runBlocking {
        val client = FakeAsdaSourceClient(validResponse)
        val result = AsdaRetailerAdapter(client).searchProducts("  cheddar  ")

        val success = assertIs<RetailProductSearchResult.PartialSuccess>(result)
        assertEquals(1, success.productsWithOffers.size)
        assertEquals("4642107", success.productsWithOffers.single().product.retailerProductId)
        assertEquals(200L, success.productsWithOffers.single().offer.price.amountMinor)
        assertEquals("cheddar", client.lastQuery)
        assertTrue(success.warnings.any { it.contains("availability", ignoreCase = true) })
    }

    @Test
    fun blankSearchDoesNotCallSource() = runBlocking {
        val client = FakeAsdaSourceClient(validResponse)

        val result = AsdaRetailerAdapter(client).searchProducts("  ")

        val unavailable = assertIs<RetailProductSearchResult.Unavailable>(result)
        assertEquals("EMPTY_QUERY", unavailable.errorCode)
        assertEquals(null, client.lastQuery)
    }

    @Test
    fun sourceFailurePreservesCachedFallback() = runBlocking {
        val client = FakeAsdaSourceClient(failure = IllegalStateException("ASDA Algolia API key not configured"))

        val result = AsdaRetailerAdapter(client).searchProducts("milk")

        val unavailable = assertIs<RetailProductSearchResult.Unavailable>(result)
        assertEquals("SOURCE_CONFIGURATION_REQUIRED", unavailable.errorCode)
        assertTrue(unavailable.cachedFallbackAvailable)
    }

    private class FakeAsdaSourceClient(
        private val response: String? = null,
        private val failure: Exception? = null
    ) : AsdaSourceClient(apiKeyProvider = { "test-key" }) {
        var lastQuery: String? = null
            private set

        override suspend fun search(query: String): String {
            lastQuery = query
            failure?.let { throw it }
            return response ?: error("Test response was not configured")
        }
    }

    private companion object {
        val validResponse = """
            {
              "results": [
                {
                  "hits": [
                    {
                      "CIN": "4642107",
                      "NAME": "Grated Mature British Cheddar 250g",
                      "BRAND": "ASDA",
                      "PACK_SIZE": "250G",
                      "PRICES": {
                        "EN": {
                          "PRICE": "2.00",
                          "WASPRICE": "2.45"
                        }
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
