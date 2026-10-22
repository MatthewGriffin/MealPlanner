package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AldiRetailerAdapterTest {
    private val productSearchHtml = """
        <html><head>
        <script type="application/ld+json">
        {
          "@context":"https://schema.org",
          "@type":"Product",
          "name":"Cowbelle Whole Milk 1 L",
          "sku":"aldi-milk-1l",
          "brand":{"@type":"Brand","name":"Cowbelle"},
          "offers":{"@type":"Offer","price":"0.95","priceCurrency":"GBP","availability":"https://schema.org/InStock"}
        }
        </script>
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun capabilityAdvertisesCataloguePricesButNotBranchAvailability() {
        val capability = AldiRetailerAdapter().getCapability()

        assertEquals(RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE, capability.mode)
        assertTrue(capability.searchSupported)
        assertTrue(capability.livePriceSupported)
        assertFalse(capability.availabilitySupported)
    }

    @Test
    fun structuredCataloguePriceIsRetailerGeneralAndStockRemainsUnknown() = runBlocking {
        val client = object : AldiSourceClient() {
            override suspend fun searchHtml(query: String) = productSearchHtml
        }
        val result = AldiRetailerAdapter(client).searchProducts("whole milk")

        val success = assertIs<RetailProductSearchResult.PartialSuccess>(result)
        assertEquals(1, success.productsWithOffers.size)
        val item = success.productsWithOffers.single()
        assertEquals(RetailerId.ALDI, item.product.retailerId)
        assertEquals(95L, item.offer.price.amountMinor)
        assertEquals(RetailPriceScope.RETAILER_GENERAL, item.offer.priceScope)
        assertEquals(RetailAvailability.UNKNOWN, item.offer.availability)
        assertTrue(success.warnings.any { it.contains("may differ in store") })
    }

    @Test
    fun unparseableSearchFallsBackWithoutInventingProducts() = runBlocking {
        val client = object : AldiSourceClient() {
            override suspend fun searchHtml(query: String) = "<html><body>Search results</body></html>"
        }
        val result = AldiRetailerAdapter(client).searchProducts("milk")

        val unavailable = assertIs<RetailProductSearchResult.Unavailable>(result)
        assertEquals("SOURCE_FORMAT_CHANGED", unavailable.errorCode)
        assertTrue(unavailable.cachedFallbackAvailable)
    }
}
