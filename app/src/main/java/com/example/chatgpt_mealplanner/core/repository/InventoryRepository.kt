package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow

interface InventoryRepository {
    fun getAllItems(): Flow<List<InventoryItem>>
    fun observeUsableInventory(): Flow<List<InventoryItem>>
    fun observeUsableInventoryByLocation(location: StorageLocation): Flow<List<InventoryItem>>
    fun observeLowStock(): Flow<List<InventoryItem>>
    fun observeUseSoon(): Flow<List<InventoryItem>>
    fun observeOutOfStock(): Flow<List<InventoryItem>>
    fun getItemsByLocation(location: StorageLocation): Flow<List<InventoryItem>>
    fun getItemsByStatus(status: ItemStatus): Flow<List<InventoryItem>>
    suspend fun getItemById(id: String): InventoryItem?
    suspend fun getItemByBarcode(barcode: String): InventoryItem?
    suspend fun getUsableItemsByCanonicalIngredientId(ingredientId: String): List<InventoryItem>
    suspend fun upsertItem(item: InventoryItem)
    suspend fun deleteItem(id: String)
}
