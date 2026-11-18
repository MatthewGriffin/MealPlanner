package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.ColumnInfo
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
    val createdAt: Long,
    /** ISO 4217 code of the receipt's currency; amounts above are in its minor units. Older receipts are pounds. */
    @ColumnInfo(defaultValue = "GBP") val currencyCode: String = "GBP"
)

@Entity(
    tableName = "receipt_spend_items",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptSpendEntity::class,
            parentColumns = ["id"],
            childColumns = ["receiptSpendId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = InventoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedInventoryItemId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ShoppingListItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedShoppingListItemId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["receiptSpendId"]),
        Index(value = ["linkedInventoryItemId"]),
        Index(value = ["linkedShoppingListItemId"])
    ]
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
    val createdAt: Long,
    val linkedInventoryItemId: String? = null,
    val linkedShoppingListItemId: String? = null
)

/** The amount of a product (per item bought) the household said it holds, so the next receipt with it is filled in. */
@Entity(
    tableName = "receipt_quantity_memory",
    indices = [Index(value = ["retailerId", "productKey"], unique = true)]
)
data class ReceiptQuantityMemoryEntity(
    @PrimaryKey val id: String,
    val retailerId: String,
    /** [com.example.chatgpt_mealplanner.core.util.IngredientNameMatcher.core] of the generic product name. */
    val productKey: String,
    val quantity: Double,
    val unit: String,
    val updatedAt: Long
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
