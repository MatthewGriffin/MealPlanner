package com.example.chatgpt_mealplanner.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class InventoryItem(
    val id: String = UUID.randomUUID().toString(),
    val barcode: String? = null,
    val canonicalIngredientId: String? = null,
    val canonicalName: String,
    val displayName: String,
    val brand: String? = null,
    val category: FoodCategory,
    val storageLocation: StorageLocation,
    val quantity: Double? = null,
    val quantityUnit: QuantityUnit? = null,
    val packCount: Int? = null,
    val packSize: Double? = null,
    val packSizeUnit: QuantityUnit? = null,
    val quantityConfidence: QuantityConfidence = QuantityConfidence.UNKNOWN,
    val status: ItemStatus = ItemStatus.AVAILABLE,
    val opened: Boolean? = null,
    val frozen: Boolean = false,
    val staple: Boolean = false,
    val purchaseDate: String? = null,
    val openedDate: String? = null,
    val useByDate: String? = null,
    val bestBeforeDate: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val revision: Long = 1L
)

@Serializable
data class InventoryTransaction(
    val id: String = UUID.randomUUID().toString(),
    val inventoryItemId: String,
    val type: TransactionType,
    val quantityChange: Double? = null,
    val unit: QuantityUnit? = null,
    val mealId: String? = null,
    val shoppingTripId: String? = null,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class DeductionRequest(
    val itemId: String,
    val amountToDeduct: Double,
    val unit: QuantityUnit,
    val mealId: String? = null,
    val isWholePack: Boolean = false
)
