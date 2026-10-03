package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingListWithItemEntities
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListItem
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingListWithItems
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.WasteRisk

@Entity(
    tableName = "shopping_list_items",
    foreignKeys = [
        ForeignKey(
            entity = ShoppingListEntity::class,
            parentColumns = ["id"],
            childColumns = ["shoppingListId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("shoppingListId"),
        Index("canonicalIngredientId")
    ]
)
data class ShoppingListItemEntity(
    @PrimaryKey
    val id: String,
    val shoppingListId: String,
    val canonicalIngredientId: String?,
    val displayName: String,
    val requiredQuantity: Double?,
    val requiredUnit: QuantityUnit?,
    val suggestedPurchaseQuantity: Double?,
    val suggestedPurchaseUnit: QuantityUnit?,
    val packCount: Int?,
    val reason: String,
    val priority: ShoppingPriority,
    val status: ShoppingItemStatus,
    val optional: Boolean,
    val sourceType: ShoppingSourceType,
    val hasMealRequirement: Boolean,
    val hasStapleRestock: Boolean,
    val mealShortfallQuantity: Double?,
    val stapleReserveTopUpQuantity: Double?,
    val expectedFinalInventory: Double?,
    val expectedFinalInventoryUnit: QuantityUnit?,
    val quantityUncertain: Boolean,
    val uncertaintyReason: String?,
    val requirementConfidence: RequirementConfidence,
    val wasteRisk: WasteRisk,
    val createdAt: Long,
    val updatedAt: Long
)

fun ShoppingListItemEntity.toDomainModel(): ShoppingListItem = ShoppingListItem(
    id = id,
    shoppingListId = shoppingListId,
    canonicalIngredientId = canonicalIngredientId,
    displayName = displayName,
    requiredQuantity = requiredQuantity,
    requiredUnit = requiredUnit,
    suggestedPurchaseQuantity = suggestedPurchaseQuantity,
    suggestedPurchaseUnit = suggestedPurchaseUnit,
    packCount = packCount,
    reason = reason,
    priority = priority,
    status = status,
    optional = optional,
    sourceType = sourceType,
    hasMealRequirement = hasMealRequirement,
    hasStapleRestock = hasStapleRestock,
    mealShortfallQuantity = mealShortfallQuantity,
    stapleReserveTopUpQuantity = stapleReserveTopUpQuantity,
    expectedFinalInventory = expectedFinalInventory,
    expectedFinalInventoryUnit = expectedFinalInventoryUnit,
    quantityUncertain = quantityUncertain,
    uncertaintyReason = uncertaintyReason,
    requirementConfidence = requirementConfidence,
    wasteRisk = wasteRisk,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ShoppingListItem.toEntity(): ShoppingListItemEntity = ShoppingListItemEntity(
    id = id,
    shoppingListId = shoppingListId,
    canonicalIngredientId = canonicalIngredientId,
    displayName = displayName,
    requiredQuantity = requiredQuantity,
    requiredUnit = requiredUnit,
    suggestedPurchaseQuantity = suggestedPurchaseQuantity,
    suggestedPurchaseUnit = suggestedPurchaseUnit,
    packCount = packCount,
    reason = reason,
    priority = priority,
    status = status,
    optional = optional,
    sourceType = sourceType,
    hasMealRequirement = hasMealRequirement,
    hasStapleRestock = hasStapleRestock,
    mealShortfallQuantity = mealShortfallQuantity,
    stapleReserveTopUpQuantity = stapleReserveTopUpQuantity,
    expectedFinalInventory = expectedFinalInventory,
    expectedFinalInventoryUnit = expectedFinalInventoryUnit,
    quantityUncertain = quantityUncertain,
    uncertaintyReason = uncertaintyReason,
    requirementConfidence = requirementConfidence,
    wasteRisk = wasteRisk,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ShoppingListWithItemEntities.toDomainModel(): ShoppingListWithItems {
    return ShoppingListWithItems(
        shoppingList = shoppingList.toDomainModel(),
        items = items.map { withSources ->
            ShoppingListItemWithSources(
                item = withSources.item.toDomainModel(),
                sources = withSources.sources.map { it.toDomainModel() }
            )
        }
    )
}
