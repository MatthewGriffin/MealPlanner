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

class TescoRetailerAdapterTest {
    private val productSearchHtml = """
        <html><head>
        <script type="application/ld+json">
        {
          "@context":"https://schema.org",
          "@type":"Product",
          "name":"Tesco British Semi Skimmed Milk 2 Pints",
          "sku":"tesco-milk-2pints",
          "brand":{"@type":"Brand","name":"Tesco"},
          "offers":{"@type":"Offer","price":"1.20","priceCurrency":"GBP","availability":"https://schema.org/InStock"}
        }
        </script>
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun capabilityDoesNotClaimBranchAvailability() {
        val capability = TescoRetailerAdapter().getCapability()

        assertEquals(RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE, capability.mode)
        assertTrue(capability.searchSupported)
        assertTrue(capability.livePriceSupported)
        assertFalse(capability.availabilitySupported)
    }

    @Test
    fun onlineCataloguePriceDoesNotBecomeBranchPriceOrStock() = runBlocking {
        val client = object : TescoSourceClient() {
            override suspend fun searchHtml(query: String) = productSearchHtml
        }
        val result = TescoRetailerAdapter(client).searchProducts("semi skimmed milk")

        val success = assertIs<RetailProductSearchResult.PartialSuccess>(result)
        val item = success.productsWithOffers.single()
        assertEquals(RetailerId.TESCO, item.product.retailerId)
        assertEquals("Tesco", item.product.brand)
        assertEquals(120L, item.offer.price.amountMinor)
        assertEquals(RetailPriceScope.ONLINE, item.offer.priceScope)
        assertEquals(RetailAvailability.UNKNOWN, item.offer.availability)
        assertTrue(success.warnings.any { it.contains("guide prices") })
    }

    @Test
    fun unparseableSearchKeepsFallbackAvailable() = runBlocking {
        val client = object : TescoSourceClient() {
            override suspend fun searchHtml(query: String) = "<html><body>Search results</body></html>"
        }
        val result = TescoRetailerAdapter(client).searchProducts("milk")

        val unavailable = assertIs<RetailProductSearchResult.Unavailable>(result)
        assertEquals("SOURCE_FORMAT_CHANGED", unavailable.errorCode)
        assertTrue(unavailable.cachedFallbackAvailable)
    }
}
