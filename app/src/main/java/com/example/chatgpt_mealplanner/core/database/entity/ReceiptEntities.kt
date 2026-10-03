package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "receipt_imports",
    indices = [Index(value = ["fingerprint"], unique = true)]
)
data class ReceiptImportEntity(
    @PrimaryKey val id: String,
    val retailerId: String,
    val purchasedAt: String,
    val totalAmountMinorPence: Long?,
    val itemCount: Int,
    val fingerprint: String,
    val sourceImagePath: String?,
    val createdAt: Long
)

@Entity(
    tableName = "receipt_import_lines",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptImportEntity::class,
            parentColumns = ["id"],
            childColumns = ["receiptImportId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["receiptImportId"])]
)
data class ReceiptImportLineEntity(
    @PrimaryKey val id: String,
    val receiptImportId: String,
    val rawText: String,
    val normalizedText: String,
    val displayName: String,
    val quantityPurchased: Int,
    val unitPriceMinorPence: Long?,
    val lineTotalMinorPence: Long?,
    val candidateCanonicalIngredientId: String?,
    val netQuantity: Double?,
    val netQuantityUnit: String?,
    val packCount: Int?,
    val confidence: String,
    val status: String,
    val createdAt: Long
)

@Entity(
    tableName = "receipt_product_mappings",
    indices = [Index(value = ["retailerId", "normalizedDescription"], unique = true)]
)
data class ReceiptProductMappingEntity(
    @PrimaryKey val id: String,
    val retailerId: String,
    val normalizedDescription: String,
    val displayName: String,
    val canonicalIngredientId: String?,
    val knownNetQuantity: Double?,
    val knownNetUnit: String?,
    val knownPackCount: Int?,
    val storageLocation: String?,
    val userConfirmed: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
