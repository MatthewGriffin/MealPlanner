package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.retail.contract.RetailProductMappingDecision
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.engine.UserProductMappingKey

interface RetailRepository {
    suspend fun getProductMappings(): Map<UserProductMappingKey, RetailProductMappingDecision>
    suspend fun saveProductMapping(
        retailerId: RetailerId,
        retailerProductId: String,
        canonicalIngredientId: String,
        decision: RetailProductMappingDecision,
        userConfirmed: Boolean
    )
    suspend fun getCandidateProductsWithOffers(): List<RetailProductWithOffer>
    suspend fun cacheProductsWithOffers(items: List<RetailProductWithOffer>)

    /** Atomically replace one retailer's catalogue; default preserves compatibility for test doubles. */
    suspend fun replaceCatalogueWithOffers(retailerId: RetailerId, items: List<RetailProductWithOffer>) {
        require(items.all { it.product.retailerId == retailerId && it.offer.retailerId == retailerId }) {
            "Catalogue items must all belong to the requested retailer."
        }
        cacheProductsWithOffers(items)
    }
}
