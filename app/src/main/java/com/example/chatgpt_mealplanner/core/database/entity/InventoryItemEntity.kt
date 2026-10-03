package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation

@Entity(
    tableName = "inventory_items",
    indices = [
        Index(value = ["barcode"]),
        Index(value = ["canonicalIngredientId"]),
        Index(value = ["category"]),
        Index(value = ["storageLocation"]),
        Index(value = ["status"])
    ]
)
data class InventoryItemEntity(
    @PrimaryKey
    val id: String,
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
    val quantityConfidence: QuantityConfidence,
    val status: ItemStatus,
    val opened: Boolean? = null,
    val frozen: Boolean,
    val staple: Boolean,
    val purchaseDate: String? = null,
    val openedDate: String? = null,
    val useByDate: String? = null,
    val bestBeforeDate: String? = null,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1L
)

fun InventoryItemEntity.toDomainModel(): InventoryItem = InventoryItem(
    id = id,
    barcode = barcode,
    canonicalIngredientId = canonicalIngredientId,
    canonicalName = canonicalName,
    displayName = displayName,
    brand = brand,
    category = category,
    storageLocation = storageLocation,
    quantity = quantity,
    quantityUnit = quantityUnit,
    packCount = packCount,
    packSize = packSize,
    packSizeUnit = packSizeUnit,
    quantityConfidence = quantityConfidence,
    status = status,
    opened = opened,
    frozen = frozen,
    staple = staple,
    purchaseDate = purchaseDate,
    openedDate = openedDate,
    useByDate = useByDate,
    bestBeforeDate = bestBeforeDate,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revision = revision
)

fun InventoryItem.toEntity(): InventoryItemEntity = InventoryItemEntity(
    id = id,
    barcode = barcode,
    canonicalIngredientId = canonicalIngredientId,
    canonicalName = canonicalName,
    displayName = displayName,
    brand = brand,
    category = category,
    storageLocation = storageLocation,
    quantity = quantity,
    quantityUnit = quantityUnit,
    packCount = packCount,
    packSize = packSize,
    packSizeUnit = packSizeUnit,
    quantityConfidence = quantityConfidence,
    status = status,
    opened = opened,
    frozen = frozen,
    staple = staple,
    purchaseDate = purchaseDate,
    openedDate = openedDate,
    useByDate = useByDate,
    bestBeforeDate = bestBeforeDate,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revision = revision
)
