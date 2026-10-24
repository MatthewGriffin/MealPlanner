package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Opt-in live smoke tests for the public catalogue integrations.
 *
 * Each retailer is independently gated so a developer can verify one source without
 * making ordinary unit tests depend on external websites. These tests validate only
 * the catalogue scope exposed by each adapter; they must not be read as branch-stock
 * checks. ASDA is intentionally excluded while its terms require express permission
 * to reuse site data and no approved feed/permission has been established.
 *
 * Examples:
 * ALDI_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*RetailerLiveSmokeTest.aldiPublicCatalogueSearch'
 * TESCO_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*RetailerLiveSmokeTest.tescoPublicCatalogueSearch'
 * SAINSBURYS_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*RetailerLiveSmokeTest.sainsburysPublicCatalogueSearch'
 * LIDL_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*RetailerLiveSmokeTest.lidlPublishedProductsSearch'
 */
class RetailerLiveSmokeTest {
    private fun requireOptIn(variable: String) {
        assumeTrue(
            "Set $variable=true to run this live-site smoke test.",
            System.getenv(variable).equals("true", ignoreCase = true)
        )
    }

    private fun productsOrFail(
        retailerId: RetailerId,
        result: RetailProductSearchResult
    ) = when (result) {
        is RetailProductSearchResult.Success -> result.productsWithOffers
        is RetailProductSearchResult.PartialSuccess -> result.productsWithOffers
        is RetailProductSearchResult.Unavailable -> fail(
            "$retailerId live search was unavailable: ${result.errorCode}: ${result.reason}"
        )
    }

    @Test
    fun aldiPublicCatalogueSearch() = runBlocking {
        requireOptIn("ALDI_LIVE_SMOKE_TEST")
        val products = productsOrFail(RetailerId.ALDI, AldiRetailerAdapter().searchProducts("milk"))
        assertTrue(products.isNotEmpty(), "ALDI returned no parsed products")
        assertTrue(products.all { it.product.retailerId == RetailerId.ALDI })
        assertTrue(products.all { it.offer.priceScope == RetailPriceScope.RETAILER_GENERAL })
        assertTrue(products.all { it.offer.availability == RetailAvailability.UNKNOWN })
    }

    @Test
    fun tescoPublicCatalogueSearch() = runBlocking {
        requireOptIn("TESCO_LIVE_SMOKE_TEST")
        val products = productsOrFail(RetailerId.TESCO, TescoRetailerAdapter().searchProducts("milk"))
        assertTrue(products.isNotEmpty(), "Tesco returned no parsed products")
        assertTrue(products.all { it.product.retailerId == RetailerId.TESCO })
        assertTrue(products.all { it.offer.priceScope == RetailPriceScope.ONLINE })
        assertTrue(products.all { it.offer.availability == RetailAvailability.UNKNOWN })
    }

    @Test
    fun sainsburysPublicCatalogueSearch() = runBlocking {
        requireOptIn("SAINSBURYS_LIVE_SMOKE_TEST")
        val products = productsOrFail(
            RetailerId.SAINSBURYS,
            AdditionalRetailerAdapter().searchProducts(RetailerId.SAINSBURYS, "milk")
        )
        assertTrue(products.isNotEmpty(), "Sainsbury's returned no parsed products")
        assertTrue(products.all { it.product.retailerId == RetailerId.SAINSBURYS })
        assertTrue(products.all { it.offer.priceScope == RetailPriceScope.ONLINE })
        assertTrue(products.all { it.offer.availability == RetailAvailability.UNKNOWN })
    }

    @Test
    fun lidlPublishedProductsSearch() = runBlocking {
        requireOptIn("LIDL_LIVE_SMOKE_TEST")
        val products = productsOrFail(
            RetailerId.LIDL,
            AdditionalRetailerAdapter().searchProducts(RetailerId.LIDL, "chocolate")
        )
        assertTrue(products.isNotEmpty(), "Lidl returned no parsed published products")
        assertTrue(products.all { it.product.retailerId == RetailerId.LIDL })
        assertTrue(products.all { it.offer.priceScope == RetailPriceScope.RETAILER_GENERAL })
        assertTrue(products.all { it.offer.availability == RetailAvailability.UNKNOWN })
        assertFalse(products.any { it.offer.storeLocationId != null }, "Public catalogue offers must not claim branch identity")
        assertEquals(1, products.map { it.offer.priceScope }.distinct().size)
    }
}
