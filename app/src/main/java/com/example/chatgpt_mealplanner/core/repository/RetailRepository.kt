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
}
