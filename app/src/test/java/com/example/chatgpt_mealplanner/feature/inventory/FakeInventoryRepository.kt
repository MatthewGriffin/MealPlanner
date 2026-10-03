package com.example.chatgpt_mealplanner.feature.inventory

import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.repository.InventoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeInventoryRepository : InventoryRepository {
    val itemsMap = mutableMapOf<String, InventoryItem>()
    val flow = MutableStateFlow<List<InventoryItem>>(emptyList())

    fun emitItems(items: List<InventoryItem>) {
        items.forEach { itemsMap[it.id] = it }
        flow.value = itemsMap.values.toList()
    }

    override fun getAllItems(): Flow<List<InventoryItem>> = flow

    override fun observeUsableInventory(): Flow<List<InventoryItem>> =
        flow.map { list -> list.filter { it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) } }

    override fun observeUsableInventoryByLocation(location: StorageLocation): Flow<List<InventoryItem>> =
        flow.map { list -> list.filter { it.storageLocation == location && it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) } }

    override fun observeLowStock(): Flow<List<InventoryItem>> =
        flow.map { list -> list.filter { it.status == ItemStatus.LOW } }

    override fun observeUseSoon(): Flow<List<InventoryItem>> =
        flow.map { list -> list.filter { it.status == ItemStatus.USE_SOON } }

    override fun observeOutOfStock(): Flow<List<InventoryItem>> =
        flow.map { list -> list.filter { it.status == ItemStatus.OUT } }

    override fun getItemsByLocation(location: StorageLocation): Flow<List<InventoryItem>> =
        flow.map { list -> list.filter { it.storageLocation == location } }

    override fun getItemsByStatus(status: ItemStatus): Flow<List<InventoryItem>> =
        flow.map { list -> list.filter { it.status == status } }

    override suspend fun getItemById(id: String): InventoryItem? = itemsMap[id]

    override suspend fun getItemByBarcode(barcode: String): InventoryItem? =
        itemsMap.values.firstOrNull { it.barcode == barcode }

    override suspend fun getUsableItemsByCanonicalIngredientId(ingredientId: String): List<InventoryItem> =
        itemsMap.values.filter { it.canonicalIngredientId == ingredientId && it.status in listOf(ItemStatus.AVAILABLE, ItemStatus.LOW, ItemStatus.USE_SOON) }

    override suspend fun upsertItem(item: InventoryItem) {
        itemsMap[item.id] = item
        flow.value = itemsMap.values.toList()
    }

    override suspend fun deleteItem(id: String) {
        itemsMap.remove(id)
        flow.value = itemsMap.values.toList()
    }
}
