package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.model.InventoryTransaction
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.TransactionType

@Entity(
    tableName = "inventory_transactions",
    foreignKeys = [
        ForeignKey(
            entity = InventoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["inventoryItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["inventoryItemId"]),
        Index(value = ["timestamp"])
    ]
)
data class InventoryTransactionEntity(
    @PrimaryKey
    val id: String,
    val inventoryItemId: String,
    val type: TransactionType,
    val quantityChange: Double? = null,
    val unit: QuantityUnit? = null,
    val mealId: String? = null,
    val shoppingTripId: String? = null,
    val note: String? = null,
    val timestamp: Long
)

fun InventoryTransactionEntity.toDomainModel(): InventoryTransaction = InventoryTransaction(
    id = id,
    inventoryItemId = inventoryItemId,
    type = type,
    quantityChange = quantityChange,
    unit = unit,
    mealId = mealId,
    shoppingTripId = shoppingTripId,
    note = note,
    timestamp = timestamp
)

fun InventoryTransaction.toEntity(): InventoryTransactionEntity = InventoryTransactionEntity(
    id = id,
    inventoryItemId = inventoryItemId,
    type = type,
    quantityChange = quantityChange,
    unit = unit,
    mealId = mealId,
    shoppingTripId = shoppingTripId,
    note = note,
    timestamp = timestamp
)
