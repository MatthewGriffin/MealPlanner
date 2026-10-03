package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingList
import com.example.chatgpt_mealplanner.core.model.ShoppingListItem
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingListWithItems
import com.example.chatgpt_mealplanner.core.model.ShoppingRequirementSource
import kotlinx.coroutines.flow.Flow

interface ShoppingRepository {
    suspend fun getActiveShoppingList(): ShoppingList?
    fun observeActiveShoppingListWithItems(): Flow<ShoppingListWithItems?>
    suspend fun getShoppingListWithItems(listId: String): ShoppingListWithItems?
    suspend fun insertShoppingList(list: ShoppingList)
    suspend fun insertShoppingListItem(item: ShoppingListItem)
    suspend fun insertShoppingListItems(items: List<ShoppingListItem>)
    suspend fun insertRequirementSources(sources: List<ShoppingRequirementSource>)
    suspend fun deleteGeneratedItemsForList(listId: String)
    suspend fun deleteShoppingListItem(itemId: String)
    suspend fun updateItemStatus(itemId: String, status: ShoppingItemStatus)
}
