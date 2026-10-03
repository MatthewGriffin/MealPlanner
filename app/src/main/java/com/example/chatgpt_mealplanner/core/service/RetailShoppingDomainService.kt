package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import com.example.chatgpt_mealplanner.retail.contract.PackAlternative
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.UserShoppingPreferences

interface RetailShoppingDomainService {
    suspend fun optimizeShoppingBasket(
        preferences: UserShoppingPreferences = UserShoppingPreferences()
    ): DomainResult<BasketComparisonResult>

    suspend fun getItemOptions(
        shoppingListItemId: String,
        retailerFilter: RetailerId? = null
    ): DomainResult<List<PackAlternative>>

    suspend fun searchProducts(
        query: String,
        retailerFilter: RetailerId? = null
    ): DomainResult<List<RetailProductWithOffer>>
}
