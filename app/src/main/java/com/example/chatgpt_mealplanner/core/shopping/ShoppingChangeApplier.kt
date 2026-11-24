package com.example.chatgpt_mealplanner.core.shopping

import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.WasteRisk
import com.example.chatgpt_mealplanner.sync.contract.ShoppingChangeDto
import com.example.chatgpt_mealplanner.sync.contract.ShoppingChangeKind
import java.util.UUID

/**
 * Applies one change to a hand-added item on the shopping list. Main uses it for changes followers send; a follower uses it
 * to put its own not-yet-uploaded changes back after Main's list replaces its copy. Only hand-added (MANUAL) items are
 * touched: the rest of the list is worked out from the meal plan and is Main's alone. Applying a change twice is harmless.
 */
class ShoppingChangeApplier(private val dao: ShoppingDao) {

    /** Returns true when the change is dealt with (applied, or nothing to do), false when it should be tried again later. */
    suspend fun apply(change: ShoppingChangeDto, requestedBy: String?): Boolean {
        val existing = dao.getShoppingListItemById(change.itemId)
        val now = System.currentTimeMillis()
        when (change.kind) {
            ShoppingChangeKind.ADD -> {
                if (existing != null) return true
                val name = change.displayName?.trim().orEmpty()
                if (name.isEmpty()) return true
                val list = dao.getActiveShoppingList() ?: createList(now)
                // The same thing already on the list (by name) is not added twice.
                val sameName = dao.getShoppingListWithItems(list.id)?.items.orEmpty()
                    .any { it.item.displayName.trim().equals(name, ignoreCase = true) }
                if (sameName) return true
                dao.insertShoppingListItem(newManualItem(change.itemId, list.id, name, change.quantity, change.unit?.toUnit(), change.reason, requestedBy, now))
            }
            ShoppingChangeKind.EDIT -> {
                if (existing == null || existing.sourceType != ShoppingSourceType.MANUAL) return true
                val unit = change.unit?.toUnit() ?: existing.requiredUnit
                dao.insertShoppingListItem(
                    existing.copy(
                        displayName = change.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.displayName,
                        requiredQuantity = change.quantity ?: existing.requiredQuantity,
                        requiredUnit = unit,
                        suggestedPurchaseQuantity = change.quantity ?: existing.suggestedPurchaseQuantity,
                        suggestedPurchaseUnit = unit,
                        reason = change.reason ?: existing.reason,
                        updatedAt = now
                    )
                )
            }
            ShoppingChangeKind.REMOVE -> {
                if (existing != null && existing.sourceType == ShoppingSourceType.MANUAL) dao.deleteShoppingListItem(change.itemId)
            }
            ShoppingChangeKind.SET_STATUS -> {
                val status = runCatching { ShoppingItemStatus.valueOf(change.status.orEmpty()) }.getOrNull()
                if (existing != null && existing.sourceType == ShoppingSourceType.MANUAL && status != null) dao.updateItemStatus(change.itemId, status.name)
            }
        }
        return true
    }

    private suspend fun createList(now: Long): ShoppingListEntity {
        val list = ShoppingListEntity(
            id = UUID.randomUUID().toString(), name = "Active Shopping List", startDate = null, endDate = null,
            status = ShoppingListStatus.ACTIVE, createdAt = now, updatedAt = now
        )
        dao.insertShoppingList(list)
        return list
    }

    private fun String.toUnit(): QuantityUnit? = runCatching { QuantityUnit.valueOf(this) }.getOrNull()

    companion object {
        const val DEFAULT_REASON = "Manually added item"

        fun newManualItem(
            id: String, listId: String, name: String, quantity: Double?, unit: QuantityUnit?, why: String?, requestedBy: String?, now: Long
        ) = ShoppingListItemEntity(
            id = id, shoppingListId = listId, canonicalIngredientId = null, displayName = name,
            requiredQuantity = quantity, requiredUnit = unit, suggestedPurchaseQuantity = quantity, suggestedPurchaseUnit = unit,
            packCount = null, reason = why?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REASON,
            priority = ShoppingPriority.ESSENTIAL, status = ShoppingItemStatus.NEEDED, optional = false,
            sourceType = ShoppingSourceType.MANUAL, hasMealRequirement = false, hasStapleRestock = false,
            mealShortfallQuantity = null, stapleReserveTopUpQuantity = quantity, expectedFinalInventory = null,
            expectedFinalInventoryUnit = unit, quantityUncertain = false, uncertaintyReason = null,
            requirementConfidence = RequirementConfidence.EXACT, wasteRisk = WasteRisk.LOW,
            createdAt = now, updatedAt = now, requestedBy = requestedBy
        )
    }
}
