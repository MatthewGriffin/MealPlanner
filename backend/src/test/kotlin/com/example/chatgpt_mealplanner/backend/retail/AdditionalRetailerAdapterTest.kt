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

class AdditionalRetailerAdapterTest {
    private val html = """
        <html><head><script type="application/ld+json">
        {
          "@context":"https://schema.org",
          "@type":"Product",
          "name":"Store Brand Semi Skimmed Milk 2 Pints",
          "sku":"test-milk-2pints",
          "brand":{"@type":"Brand","name":"Store Brand"},
          "offers":{"@type":"Offer","price":"1.10","priceCurrency":"GBP","availability":"https://schema.org/InStock"}
        }
        </script></head><body></body></html>
    """.trimIndent()

    @Test
    fun capabilitiesNeverClaimStockOrPromotions() {
        listOf(RetailerId.MORRISONS, RetailerId.SAINSBURYS, RetailerId.LIDL).forEach { id ->
            val capability = AdditionalRetailerAdapter().getCapability(id)
            assertEquals(RetailerCapabilityMode.LIVE_SEARCH_AVAILABLE, capability.mode)
            assertTrue(capability.searchSupported)
            assertTrue(capability.livePriceSupported)
            assertFalse(capability.availabilitySupported)
            assertFalse(capability.promotionsSupported)
        }
    }

    @Test
    fun cataloguePricesAreScopedAndStockRemainsUnknown() = runBlocking {
        val sainsburysJson = """{"products":[{"product_uid":"sainsburys-milk-1","name":"Store Brand Semi Skimmed Milk 2 Pints","retail_price":"1.10","in_stock":true,"image_url":"https://example.invalid/milk.jpg"}]}"""
        val client = object : AdditionalRetailerSourceClient() {
            override suspend fun searchHtml(retailerId: RetailerId, query: String) =
                if (retailerId == RetailerId.SAINSBURYS) sainsburysJson else html
        }
        val adapter = AdditionalRetailerAdapter(client)
        listOf(
            RetailerId.MORRISONS to RetailPriceScope.ONLINE,
            RetailerId.SAINSBURYS to RetailPriceScope.ONLINE,
            RetailerId.LIDL to RetailPriceScope.RETAILER_GENERAL
        ).forEach { (id, expectedScope) ->
            val result = assertIs<RetailProductSearchResult.PartialSuccess>(adapter.searchProducts(id, "milk"))
            val item = result.productsWithOffers.single()
            assertEquals(id, item.product.retailerId)
            assertEquals(110L, item.offer.price.amountMinor)
            assertEquals(expectedScope, item.offer.priceScope)
            assertEquals(RetailAvailability.UNKNOWN, item.offer.availability)
        }
    }


    @Test
    fun sainsburysCataloguePageParserMapsOnlyPricedProductsAndIgnoresStock() {
        val raw = """{
          "total_record_count": 2,
          "products": [
            {"product_uid":"catalogue-milk","name":"Milk","retail_price":"1.25","in_stock":true},
            {"product_uid":"catalogue-eggs","name":"Eggs","retail_price":"2.00","in_stock":false}
          ]
        }"""
        val products = AdditionalRetailerAdapter().parseSainsburysJson(raw)
        assertEquals(2, products.size)
        assertEquals(listOf("catalogue-milk", "catalogue-eggs"), products.map { it.product.retailerProductId })
        assertEquals(listOf(125L, 200L), products.map { it.offer.price.amountMinor })
        assertTrue(products.all { it.offer.priceScope == RetailPriceScope.ONLINE })
        assertTrue(products.all { it.offer.availability == RetailAvailability.UNKNOWN })
    }

    @Test
    fun blankQueryAndSourceFailureReturnStructuredFallbackResults() = runBlocking {
        val blankResult = AdditionalRetailerAdapter().searchProducts(RetailerId.MORRISONS, "   ")
        val blankUnavailable = assertIs<RetailProductSearchResult.Unavailable>(blankResult)
        assertEquals("EMPTY_QUERY", blankUnavailable.errorCode)
        assertTrue(blankUnavailable.cachedFallbackAvailable)

        val failingClient = object : AdditionalRetailerSourceClient() {
            override suspend fun searchHtml(retailerId: RetailerId, query: String): String {
                throw IllegalStateException("fixture source failure")
            }
        }
        val sourceResult = AdditionalRetailerAdapter(failingClient).searchProducts(RetailerId.SAINSBURYS, "milk")
        val sourceUnavailable = assertIs<RetailProductSearchResult.Unavailable>(sourceResult)
        assertEquals("SOURCE_UNAVAILABLE", sourceUnavailable.errorCode)
        assertTrue(sourceUnavailable.reason.contains("fixture source failure"))
        assertTrue(sourceUnavailable.cachedFallbackAvailable)
    }

    @Test
    fun parserFailureKeepsCachedFallbackAvailable() = runBlocking {
        val client = object : AdditionalRetailerSourceClient() {
            override suspend fun searchHtml(retailerId: RetailerId, query: String) = "<html><body>no structured products</body></html>"
        }
        val result = AdditionalRetailerAdapter(client).searchProducts(RetailerId.MORRISONS, "milk")
        val unavailable = assertIs<RetailProductSearchResult.Unavailable>(result)
        assertEquals("SOURCE_FORMAT_CHANGED", unavailable.errorCode)
        assertTrue(unavailable.cachedFallbackAvailable)
    }
}
