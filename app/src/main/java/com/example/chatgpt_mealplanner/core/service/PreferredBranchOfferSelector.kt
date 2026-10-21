package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreLocation

/**
 * Keeps offers from unselected physical branches out of a preferred-branch comparison.
 *
 * The app-local [RetailStoreLocation.id] is not a retailer branch identifier. Only
 * [RetailStoreLocation.retailerStoreId], populated from an authorised retailer directory/feed,
 * may match an offer's storeLocationId. If a preferred branch has no retailer ID yet, its
 * branch-scoped offers cannot be verified and are excluded; online/general offers remain usable.
 */
object PreferredBranchOfferSelector {
    fun select(
        candidates: List<RetailProductWithOffer>,
        preferredStores: List<RetailStoreLocation>
    ): List<RetailProductWithOffer> {
        val preferredRetailers = preferredStores
            .filter { it.isPreferred }
            .map { it.retailerId }
            .toSet()
        if (preferredRetailers.isEmpty()) return candidates

        val preferredSourceIdsByRetailer = preferredStores
            .filter { it.isPreferred && !it.retailerStoreId.isNullOrBlank() }
            .groupBy({ it.retailerId }, { it.retailerStoreId!! })
            .mapValues { (_, ids) -> ids.toSet() }

        fun isBranchScoped(candidate: RetailProductWithOffer): Boolean =
            candidate.offer.priceScope == RetailPriceScope.STORE_SPECIFIC ||
                candidate.offer.priceScope == RetailPriceScope.IN_STORE

        val validSelectedBranchOffers = candidates.filter { candidate ->
            val selectedIds = preferredSourceIdsByRetailer[candidate.product.retailerId]
                ?: return@filter false
            candidate.offer.storeLocationId?.let { it in selectedIds } == true &&
                isBranchScoped(candidate) &&
                candidate.offer.availability != RetailAvailability.OUT_OF_STOCK
        }

        val selectedBranchProductKeys = validSelectedBranchOffers.map {
            it.product.retailerId to it.product.retailerProductId
        }.toSet()

        return candidates.filter { candidate ->
            val retailer = candidate.product.retailerId
            if (retailer !in preferredRetailers) return@filter true

            val branchId = candidate.offer.storeLocationId
            if (branchId != null) {
                val selectedIds = preferredSourceIdsByRetailer[retailer].orEmpty()
                branchId in selectedIds &&
                    isBranchScoped(candidate) &&
                    candidate.offer.availability != RetailAvailability.OUT_OF_STOCK
            } else {
                (retailer to candidate.product.retailerProductId) !in selectedBranchProductKeys
            }
        }
    }
}
