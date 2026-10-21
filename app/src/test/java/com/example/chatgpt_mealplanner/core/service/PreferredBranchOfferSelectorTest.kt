package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreFormat
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreLocation
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferredBranchOfferSelectorTest {
    private val selectedStore = RetailStoreLocation(
        id = "local-preference-uuid",
        retailerId = RetailerId.TESCO,
        name = "High Street",
        postcode = "AB1 2CD",
        format = RetailStoreFormat.TESCO_EXPRESS,
        retailerStoreId = "tesco-branch-a"
    )

    private fun offer(
        retailer: RetailerId,
        productId: String,
        priceScope: RetailPriceScope,
        storeId: String? = null,
        availability: RetailAvailability = RetailAvailability.UNKNOWN
    ) = RetailProductWithOffer(
        product = RetailProduct(
            retailerId = retailer,
            retailerProductId = productId,
            name = "Product $productId"
        ),
        offer = RetailOffer(
            retailerId = retailer,
            retailerProductId = productId,
            price = Money(100L),
            priceScope = priceScope,
            storeLocationId = storeId,
            availability = availability
        )
    )

    @Test
    fun selectedBranchOfferWinsOverOnlineAndOtherBranchesForSameProduct() {
        val selectedBranchOffer = offer(
            RetailerId.TESCO, "milk", RetailPriceScope.STORE_SPECIFIC,
            selectedStore.retailerStoreId, RetailAvailability.IN_STOCK
        )
        val otherBranchOffer = offer(
            RetailerId.TESCO, "milk", RetailPriceScope.IN_STORE,
            "tesco-branch-b", RetailAvailability.IN_STOCK
        )
        val onlineDuplicate = offer(RetailerId.TESCO, "milk", RetailPriceScope.ONLINE)
        val onlineOtherProduct = offer(RetailerId.TESCO, "bread", RetailPriceScope.ONLINE)
        val otherRetailerOffer = offer(RetailerId.ICELAND, "peas", RetailPriceScope.STORE_SPECIFIC, "iceland-1")

        val result = PreferredBranchOfferSelector.select(
            listOf(selectedBranchOffer, otherBranchOffer, onlineDuplicate, onlineOtherProduct, otherRetailerOffer),
            listOf(selectedStore)
        )

        assertEquals(
            listOf(selectedBranchOffer, onlineOtherProduct, otherRetailerOffer),
            result
        )
    }

    @Test
    fun branchSpecificOutOfStockOfferIsNotSelectedAndDoesNotHideOnlinePrice() {
        val outOfStock = offer(
            RetailerId.TESCO, "eggs", RetailPriceScope.STORE_SPECIFIC,
            selectedStore.retailerStoreId, RetailAvailability.OUT_OF_STOCK
        )
        val online = offer(RetailerId.TESCO, "eggs", RetailPriceScope.ONLINE)

        assertEquals(
            listOf(online),
            PreferredBranchOfferSelector.select(listOf(outOfStock, online), listOf(selectedStore))
        )
    }

    @Test
    fun localPreferenceIdIsNotMistakenForRetailerBranchId() {
        val storeWithoutSourceId = selectedStore.copy(retailerStoreId = null)
        val unverifiableBranchOffer = offer(
            RetailerId.TESCO, "milk", RetailPriceScope.STORE_SPECIFIC,
            storeWithoutSourceId.id, RetailAvailability.IN_STOCK
        )
        val online = offer(RetailerId.TESCO, "milk", RetailPriceScope.ONLINE)

        assertEquals(
            listOf(online),
            PreferredBranchOfferSelector.select(
                listOf(unverifiableBranchOffer, online),
                listOf(storeWithoutSourceId)
            )
        )
    }

    @Test
    fun noPreferredBranchesPreservesExistingCandidateSet() {
        val candidates = listOf(
            offer(RetailerId.TESCO, "milk", RetailPriceScope.ONLINE),
            offer(RetailerId.TESCO, "bread", RetailPriceScope.IN_STORE, "some-branch")
        )

        assertEquals(candidates, PreferredBranchOfferSelector.select(candidates, emptyList()))
    }
}
