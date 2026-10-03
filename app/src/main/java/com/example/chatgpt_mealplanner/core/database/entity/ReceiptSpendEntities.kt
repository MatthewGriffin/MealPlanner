package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "receipt_spend",
    indices = [Index(value = ["fingerprint"], unique = true)]
)
data class ReceiptSpendEntity(
    @PrimaryKey val id: String,
    val retailerId: String,
    val purchasedAt: String,
    val totalMinorPence: Long,
    val subtotalMinorPence: Long?,
    val discountMinorPence: Long,
    val itemCount: Int,
    val fingerprint: String,
    val createdAt: Long
)

@Entity(
    tableName = "receipt_spend_items",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptSpendEntity::class,
            parentColumns = ["id"],
            childColumns = ["receiptSpendId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["receiptSpendId"])]
)
data class ReceiptSpendItemEntity(
    @PrimaryKey val id: String,
    val receiptSpendId: String,
    val rawDescription: String,
    val normalizedDescription: String,
    val lineAmountMinorPence: Long,
    val quantity: Int,
    val unitPriceMinorPence: Long?,
    val category: String,
    val isDiscount: Boolean,
    val createdAt: Long
)

@Entity(
    tableName = "receipt_category_mappings",
    indices = [Index(value = ["retailerId", "normalizedDescription"], unique = true)]
)
data class ReceiptCategoryMappingEntity(
    @PrimaryKey val id: String,
    val retailerId: String,
    val normalizedDescription: String,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long
)
