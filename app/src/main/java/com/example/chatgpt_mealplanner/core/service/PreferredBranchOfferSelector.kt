package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailPriceScope
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreLocation

/**
 * Prevents a known branch-specific offer from another branch being used for a user's selected
 * branch. If the selected branch has an offer for the same retailer product, prefer that offer
 * over a retailer-wide online/general offer for the same product.
 *
 * Offers without branch identity remain usable as online/general price evidence, but they are
 * never promoted into branch-confirmed stock evidence.
 */
object PreferredBranchOfferSelector {
    fun select(
        candidates: List<RetailProductWithOffer>,
        preferredStores: List<RetailStoreLocation>
    ): List<RetailProductWithOffer> {
        val preferredIdsByRetailer = preferredStores
            .filter { it.isPreferred }
            .groupBy({ it.retailerId }, { it.id })
            .mapValues { (_, ids) -> ids.toSet() }

        if (preferredIdsByRetailer.isEmpty()) return candidates

        fun isBranchScoped(candidate: RetailProductWithOffer): Boolean =
            candidate.offer.priceScope == RetailPriceScope.STORE_SPECIFIC ||
                candidate.offer.priceScope == RetailPriceScope.IN_STORE

        val validSelectedBranchOffers = candidates.filter { candidate ->
            val selectedIds = preferredIdsByRetailer[candidate.product.retailerId] ?: return@filter false
            candidate.offer.storeLocationId?.let { it in selectedIds } == true &&
                isBranchScoped(candidate) &&
                candidate.offer.availability != RetailAvailability.OUT_OF_STOCK
        }

        val selectedBranchProductKeys = validSelectedBranchOffers.map {
            it.product.retailerId to it.product.retailerProductId
        }.toSet()

        return candidates.filter { candidate ->
            val selectedIds = preferredIdsByRetailer[candidate.product.retailerId]
                ?: return@filter true
            val branchId = candidate.offer.storeLocationId

            if (branchId != null) {
                branchId in selectedIds &&
                    isBranchScoped(candidate) &&
                    candidate.offer.availability != RetailAvailability.OUT_OF_STOCK
            } else {
                (candidate.product.retailerId to candidate.product.retailerProductId) !in selectedBranchProductKeys
            }
        }
    }
}
