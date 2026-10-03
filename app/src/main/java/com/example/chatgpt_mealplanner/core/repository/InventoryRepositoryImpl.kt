package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepositoryImpl @Inject constructor(
    private val inventoryDao: InventoryDao
) : InventoryRepository {

    override fun getAllItems(): Flow<List<InventoryItem>> =
        inventoryDao.getAllItems().map { list -> list.map { it.toDomainModel() } }

    override fun observeUsableInventory(): Flow<List<InventoryItem>> =
        inventoryDao.getUsableItems().map { list -> list.map { it.toDomainModel() } }

    override fun observeUsableInventoryByLocation(location: StorageLocation): Flow<List<InventoryItem>> =
        inventoryDao.getUsableItemsByLocation(location).map { list -> list.map { it.toDomainModel() } }

    override fun observeLowStock(): Flow<List<InventoryItem>> =
        inventoryDao.getLowStockItems().map { list -> list.map { it.toDomainModel() } }

    override fun observeUseSoon(): Flow<List<InventoryItem>> =
        inventoryDao.getUseSoonItems().map { list -> list.map { it.toDomainModel() } }

    override fun observeOutOfStock(): Flow<List<InventoryItem>> =
        inventoryDao.getOutOfStockItems().map { list -> list.map { it.toDomainModel() } }

    override fun getItemsByLocation(location: StorageLocation): Flow<List<InventoryItem>> =
        inventoryDao.getItemsByLocation(location).map { list -> list.map { it.toDomainModel() } }

    override fun getItemsByStatus(status: ItemStatus): Flow<List<InventoryItem>> =
        inventoryDao.getItemsByStatus(status).map { list -> list.map { it.toDomainModel() } }

    override suspend fun getItemById(id: String): InventoryItem? =
        inventoryDao.getItemById(id)?.toDomainModel()

    override suspend fun getItemByBarcode(barcode: String): InventoryItem? =
        inventoryDao.getItemByBarcode(barcode)?.toDomainModel()

    override suspend fun getUsableItemsByCanonicalIngredientId(ingredientId: String): List<InventoryItem> =
        inventoryDao.getUsableItemsByCanonicalIngredientId(ingredientId).map { it.toDomainModel() }

    override suspend fun upsertItem(item: InventoryItem) {
        inventoryDao.insertItem(item.toEntity())
    }

    override suspend fun deleteItem(id: String) {
        inventoryDao.deleteItemById(id)
    }
}
