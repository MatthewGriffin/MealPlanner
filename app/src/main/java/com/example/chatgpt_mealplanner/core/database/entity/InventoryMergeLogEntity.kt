package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "inventory_merge_logs",
    indices = [
        Index(value = ["survivorItemId"]),
        Index(value = ["mergedItemId"])
    ]
)
data class InventoryMergeLogEntity(
    @PrimaryKey
    val id: String,
    val survivorItemId: String,
    val mergedItemId: String,
    val mergedItemSnapshotJson: String,
    val mergedRuleSnapshotJson: String? = null,
    val transferredBarcodesJson: String,
    val transferredQuantity: Double,
    val transferredPackCount: Int,
    val mergedAt: Long,
    val unmerged: Boolean = false
)
