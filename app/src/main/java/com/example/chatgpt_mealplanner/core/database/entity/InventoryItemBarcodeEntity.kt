package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "inventory_item_barcodes",
    foreignKeys = [
        ForeignKey(
            entity = InventoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["inventoryItemId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["normalizedBarcode"], unique = true),
        Index(value = ["inventoryItemId"])
    ]
)
data class InventoryItemBarcodeEntity(
    @PrimaryKey
    val id: String,
    val inventoryItemId: String,
    val rawBarcode: String,
    val normalizedBarcode: String,
    val addedAt: Long,
    val source: String = "SCAN"
)
