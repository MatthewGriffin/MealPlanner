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

    /** Optional non-blocking UI progress updates while retailer lookups are running. */
    suspend fun optimizeShoppingBasket(
        preferences: UserShoppingPreferences,
        onLookupProgress: (String) -> Unit
    ): DomainResult<BasketComparisonResult> = optimizeShoppingBasket(preferences)

    /** Emits an updated basket plan as each shopping-list item has been searched. */
    suspend fun optimizeShoppingBasket(
        preferences: UserShoppingPreferences,
        onLookupProgress: (String) -> Unit,
        onBasketUpdate: (BasketComparisonResult) -> Unit
    ): DomainResult<BasketComparisonResult> = optimizeShoppingBasket(preferences, onLookupProgress)

    suspend fun getItemOptions(
        shoppingListItemId: String,
        retailerFilter: RetailerId? = null
    ): DomainResult<List<PackAlternative>>

    suspend fun searchProducts(
        query: String,
        retailerFilter: RetailerId? = null
    ): DomainResult<List<RetailProductWithOffer>>
}
