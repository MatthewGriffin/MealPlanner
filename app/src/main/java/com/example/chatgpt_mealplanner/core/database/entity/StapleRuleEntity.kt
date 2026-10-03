package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StapleRule
import com.example.chatgpt_mealplanner.core.model.StapleRuleMode
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "staple_rules",
    indices = [
        Index(value = ["canonicalIngredientId"], unique = true)
    ]
)
data class StapleRuleEntity(
    @PrimaryKey
    val id: String,
    val canonicalIngredientId: String,
    val displayName: String,
    val enabled: Boolean,
    val archivedByMerge: Boolean = false,
    val mode: StapleRuleMode,
    val thresholdQuantity: Double?,
    val thresholdUnit: QuantityUnit?,
    val targetQuantity: Double?,
    val targetUnit: QuantityUnit?,
    val thresholdPackCount: Int?,
    val targetPackCount: Int?,
    val preferredStorageLocation: StorageLocation?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long
)

fun StapleRuleEntity.toDomainModel(): StapleRule = StapleRule(
    id = id,
    canonicalIngredientId = canonicalIngredientId,
    displayName = displayName,
    enabled = enabled,
    archivedByMerge = archivedByMerge,
    mode = mode,
    thresholdQuantity = thresholdQuantity,
    thresholdUnit = thresholdUnit,
    targetQuantity = targetQuantity,
    targetUnit = targetUnit,
    thresholdPackCount = thresholdPackCount,
    targetPackCount = targetPackCount,
    preferredStorageLocation = preferredStorageLocation,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun StapleRule.toEntity(): StapleRuleEntity = StapleRuleEntity(
    id = id,
    canonicalIngredientId = canonicalIngredientId,
    displayName = displayName,
    enabled = enabled,
    archivedByMerge = archivedByMerge,
    mode = mode,
    thresholdQuantity = thresholdQuantity,
    thresholdUnit = thresholdUnit,
    targetQuantity = targetQuantity,
    targetUnit = targetUnit,
    thresholdPackCount = thresholdPackCount,
    targetPackCount = targetPackCount,
    preferredStorageLocation = preferredStorageLocation,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt
)
