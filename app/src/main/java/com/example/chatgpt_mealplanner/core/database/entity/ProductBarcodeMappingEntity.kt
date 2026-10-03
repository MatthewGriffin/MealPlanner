package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.barcode.model.ProductDataSource
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMatchConfidence
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation

@Entity(tableName = "product_barcode_mappings")
data class ProductBarcodeMappingEntity(
    @PrimaryKey val barcode: String,
    val displayName: String?,
    val brand: String?,
    val canonicalIngredientId: String?,
    val canonicalName: String?,
    val category: FoodCategory?,
    val defaultStorageLocation: StorageLocation?,
    val netQuantity: Double?,
    val netQuantityUnit: QuantityUnit?,
    val packCount: Int?,
    val packSize: Double?,
    val packSizeUnit: QuantityUnit?,
    val imageUrl: String?,
    val source: ProductDataSource,
    val confidence: ProductMatchConfidence,
    val lastVerifiedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

fun ProductBarcodeMappingEntity.toMetadata(): ProductMetadata = ProductMetadata(
    barcode = barcode,
    displayName = displayName,
    brand = brand,
    canonicalIngredientId = canonicalIngredientId,
    canonicalName = canonicalName,
    category = category,
    suggestedStorageLocation = defaultStorageLocation,
    netQuantity = netQuantity,
    netQuantityUnit = netQuantityUnit,
    packCount = packCount,
    packSize = packSize,
    packSizeUnit = packSizeUnit,
    imageUrl = imageUrl,
    source = source,
    confidence = confidence
)

fun ProductMetadata.toMappingEntity(): ProductBarcodeMappingEntity = ProductBarcodeMappingEntity(
    barcode = barcode,
    displayName = displayName,
    brand = brand,
    canonicalIngredientId = canonicalIngredientId,
    canonicalName = canonicalName,
    category = category,
    defaultStorageLocation = suggestedStorageLocation,
    netQuantity = netQuantity,
    netQuantityUnit = netQuantityUnit,
    packCount = packCount,
    packSize = packSize,
    packSizeUnit = packSizeUnit,
    imageUrl = imageUrl,
    source = source,
    confidence = confidence,
    lastVerifiedAt = System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis()
)
