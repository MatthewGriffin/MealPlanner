package com.example.chatgpt_mealplanner.core.worker

import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcelandStarterCatalogueTest {

    @Test
    fun starterCatalogueHasUniqueIcelandProductsWithExplicitOnlineEstimateScope() {
        val products = IcelandStarterCatalogue.products()

        assertEquals(19, products.size)
        assertEquals(
            products.size,
            products.map { it.product.retailerProductId }.distinct().size
        )
        assertTrue(products.all { it.product.retailerId == RetailerId.ICELAND })
        assertTrue(products.all { it.offer.retailerId == RetailerId.ICELAND })
        assertTrue(products.all { it.offer.priceScope == RetailPriceScope.ONLINE })
        assertTrue(products.all { it.offer.availability == RetailAvailability.UNKNOWN })
        assertTrue(products.all { it.product.retailerProductId.startsWith("STARTER_") })
        assertFalse(products.any { it.offer.price.amountMinor <= 0L })
        assertTrue(products.all { it.offer.observedAt == IcelandStarterCatalogue.CHECKED_AT })
    }
}
