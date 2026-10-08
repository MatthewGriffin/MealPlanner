package com.example.chatgpt_mealplanner.core.sync.follower.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "follower_inventory_items")
data class FollowerInventoryItemEntity(
    @PrimaryKey val itemId: String,
    val barcode: String? = null,
    val canonicalName: String,
    val confirmedDisplayName: String,
    val confirmedBrand: String? = null,
    val confirmedCategory: String,
    val confirmedStorageLocation: String,
    val confirmedQuantity: Double? = null,
    val confirmedQuantityUnit: String? = null,
    val confirmedNotes: String? = null,
    val confirmedStatus: String,
    val mainConfirmedRevision: Long = 1L,
    val displayName: String,
    val brand: String? = null,
    val category: String,
    val storageLocation: String,
    val quantity: Double? = null,
    val quantityUnit: String? = null,
    val notes: String? = null,
    val status: String,
    val optimisticRevision: Long = 1L,
    val isPendingSync: Boolean = false,
    val updatedAt: Long
)

fun FollowerInventoryItemEntity.toDomainModel(): com.example.chatgpt_mealplanner.core.model.InventoryItem {
    val parsedCategory = runCatching { com.example.chatgpt_mealplanner.core.model.FoodCategory.valueOf(category) }.getOrDefault(com.example.chatgpt_mealplanner.core.model.FoodCategory.OTHER)
    val parsedLocation = runCatching { com.example.chatgpt_mealplanner.core.model.StorageLocation.valueOf(storageLocation) }.getOrDefault(com.example.chatgpt_mealplanner.core.model.StorageLocation.CUPBOARD)
    val parsedStatus = runCatching { com.example.chatgpt_mealplanner.core.model.ItemStatus.valueOf(status) }.getOrDefault(com.example.chatgpt_mealplanner.core.model.ItemStatus.AVAILABLE)
    val parsedUnit = quantityUnit?.let { com.example.chatgpt_mealplanner.core.model.parseQuantityUnit(it) }

    return com.example.chatgpt_mealplanner.core.model.InventoryItem(
        id = itemId,
        barcode = barcode,
        canonicalName = canonicalName,
        displayName = displayName,
        brand = brand,
        category = parsedCategory,
        storageLocation = parsedLocation,
        quantity = quantity,
        quantityUnit = parsedUnit,
        notes = notes,
        status = parsedStatus,
        revision = optimisticRevision,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )
}
