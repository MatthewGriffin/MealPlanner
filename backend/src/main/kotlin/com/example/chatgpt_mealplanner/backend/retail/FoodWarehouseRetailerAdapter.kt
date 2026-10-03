package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityDto
import com.example.chatgpt_mealplanner.retail.contract.RetailerCapabilityMode
import com.example.chatgpt_mealplanner.retail.contract.RetailerId

class FoodWarehouseRetailerAdapter {

    fun getCapability(): RetailerCapabilityDto {
        return RetailerCapabilityDto(
            retailerId = RetailerId.FOOD_WAREHOUSE,
            mode = RetailerCapabilityMode.SHARED_CATALOGUE_WITH_ICELAND,
            searchSupported = false,
            livePriceSupported = false,
            availabilitySupported = false,
            promotionsSupported = false,
            description = "The Food Warehouse shares Iceland catalog data. Physical store prices and bulk offers use manual/fixture inputs."
        )
    }

    suspend fun searchProducts(query: String): RetailProductSearchResult {
        return RetailProductSearchResult.Unavailable(
            reason = "Food Warehouse shares catalog infrastructure with Iceland. Physical store bulk prices require manual/fixture verification.",
            errorCode = "SHARED_CATALOGUE_ONLY",
            cachedFallbackAvailable = true
        )
    }
}
