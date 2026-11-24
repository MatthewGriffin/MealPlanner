package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListItem
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingListWithItems

interface ShoppingDomainService {

    suspend fun calculateShoppingRequirements(
        startDate: String? = null,
        endDate: String? = null
    ): DomainResult<List<ShoppingListItemWithSources>>

    suspend fun generateOrRegenerateShoppingList(
        listName: String = "Active Shopping List",
        startDate: String? = null,
        endDate: String? = null
    ): DomainResult<ShoppingListWithItems>

    suspend fun addManualShoppingItem(
        shoppingListId: String,
        displayName: String,
        quantity: Double? = null,
        unit: QuantityUnit? = null,
        notes: String? = null,
        /** A fixed id, so the same request (for example an assistant tool call) never adds the item twice. */
        itemId: String? = null
    ): DomainResult<ShoppingListItem>

    /** Renames a hand-added item or changes its quantity or its "why". Null leaves a field as it is. */
    suspend fun updateManualItem(
        itemId: String,
        displayName: String? = null,
        quantity: Double? = null,
        unit: QuantityUnit? = null,
        reason: String? = null
    ): DomainResult<Unit>

    suspend fun updateItemStatus(
        itemId: String,
        status: ShoppingItemStatus
    ): DomainResult<Unit>

    suspend fun removeShoppingItem(
        itemId: String
    ): DomainResult<Unit>

    suspend fun getActiveShoppingList(): DomainResult<ShoppingListWithItems?>

    suspend fun resolveInventoryLotUncertainty(
        inventoryItemId: String,
        confirmedQuantity: Double,
        unit: QuantityUnit,
        resultingConfidence: QuantityConfidence = QuantityConfidence.EXACT
    ): DomainResult<Unit>
}
