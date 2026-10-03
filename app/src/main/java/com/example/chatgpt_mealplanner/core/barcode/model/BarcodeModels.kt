package com.example.chatgpt_mealplanner.core.barcode.model

import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation

enum class ProductMatchConfidence {
    EXACT, HIGH, MEDIUM, LOW, UNKNOWN
}

enum class ProductDataSource {
    OPEN_FOOD_FACTS, OPEN_BEAUTY_FACTS, OPEN_PRODUCTS_FACTS, MANUAL, FUTURE_RETAILER_SOURCE
}

data class ProductMetadata(
    val barcode: String,
    val displayName: String?,
    val brand: String? = null,
    val canonicalIngredientId: String? = null,
    val canonicalName: String? = null,
    val category: FoodCategory? = null,
    val suggestedStorageLocation: StorageLocation? = null,
    val netQuantity: Double? = null,
    val netQuantityUnit: QuantityUnit? = null,
    val packCount: Int? = null,
    val packSize: Double? = null,
    val packSizeUnit: QuantityUnit? = null,
    val imageUrl: String? = null,
    val source: ProductDataSource = ProductDataSource.OPEN_FOOD_FACTS,
    val rawSourceId: String? = null,
    val confidence: ProductMatchConfidence = ProductMatchConfidence.HIGH
)

data class ConfirmedProductInput(
    val barcode: String?,
    val displayName: String,
    val brand: String? = null,
    val canonicalIngredientId: String? = null,
    val canonicalName: String? = null,
    val category: FoodCategory = FoodCategory.CUPBOARD,
    val storageLocation: StorageLocation = StorageLocation.CUPBOARD,
    val netQuantity: Double? = null,
    val netQuantityUnit: QuantityUnit? = null,
    val packCount: Int? = null,
    val packSize: Double? = null,
    val packSizeUnit: QuantityUnit? = null,
    val quantityConfidence: QuantityConfidence = QuantityConfidence.EXACT,
    val opened: Boolean = false,
    val purchaseDate: String? = null,
    val openedDate: String? = null,
    val useByDate: String? = null,
    val bestBeforeDate: String? = null,
    val notes: String? = null,
    val staple: Boolean = false
)

sealed interface ProductLookupResult {
    data class Found(
        val metadata: ProductMetadata,
        val isFromCache: Boolean
    ) : ProductLookupResult

    data class NotFound(val barcode: String) : ProductLookupResult
    data class Error(val barcode: String, val error: DomainError) : ProductLookupResult
}
