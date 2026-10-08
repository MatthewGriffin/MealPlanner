package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepositoryImpl @Inject constructor(
    private val inventoryDao: InventoryDao,
    private val appRoleResolver: AppRoleResolver? = null,
    private val followerDatabase: FollowerDatabase? = null,
    private val followerLocalSyncEngine: FollowerLocalSyncEngine? = null,
    private val followerIdentityStore: FollowerIdentityStore? = null
) : InventoryRepository {

    private fun isFollower(): Boolean = appRoleResolver?.isFollower() == true

    override fun getAllItems(): Flow<List<InventoryItem>> {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.observeAllItems().map { list -> list.map { it.toDomainModel() } }
        }
        return inventoryDao.getAllItems().map { list -> list.map { it.toDomainModel() } }
    }

    override fun observeUsableInventory(): Flow<List<InventoryItem>> {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.observeUsableItems().map { list -> list.map { it.toDomainModel() } }
        }
        return inventoryDao.getUsableItems().map { list -> list.map { it.toDomainModel() } }
    }

    override fun observeUsableInventoryByLocation(location: StorageLocation): Flow<List<InventoryItem>> {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.observeUsableItemsByLocation(location.name).map { list -> list.map { it.toDomainModel() } }
        }
        return inventoryDao.getUsableItemsByLocation(location).map { list -> list.map { it.toDomainModel() } }
    }

    override fun observeLowStock(): Flow<List<InventoryItem>> {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.observeLowStockItems().map { list -> list.map { it.toDomainModel() } }
        }
        return inventoryDao.getLowStockItems().map { list -> list.map { it.toDomainModel() } }
    }

    override fun observeUseSoon(): Flow<List<InventoryItem>> {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.observeUseSoonItems().map { list -> list.map { it.toDomainModel() } }
        }
        return inventoryDao.getUseSoonItems().map { list -> list.map { it.toDomainModel() } }
    }

    override fun observeOutOfStock(): Flow<List<InventoryItem>> {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.observeOutOfStockItems().map { list -> list.map { it.toDomainModel() } }
        }
        return inventoryDao.getOutOfStockItems().map { list -> list.map { it.toDomainModel() } }
    }

    override fun getItemsByLocation(location: StorageLocation): Flow<List<InventoryItem>> {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.observeItemsByLocation(location.name).map { list -> list.map { it.toDomainModel() } }
        }
        return inventoryDao.getItemsByLocation(location).map { list -> list.map { it.toDomainModel() } }
    }

    override fun getItemsByStatus(status: ItemStatus): Flow<List<InventoryItem>> {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.observeItemsByStatus(status.name).map { list -> list.map { it.toDomainModel() } }
        }
        return inventoryDao.getItemsByStatus(status).map { list -> list.map { it.toDomainModel() } }
    }

    override suspend fun getItemById(id: String): InventoryItem? {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.getItemById(id)?.toDomainModel()
        }
        return inventoryDao.getItemById(id)?.toDomainModel()
    }

    override suspend fun getItemByBarcode(barcode: String): InventoryItem? {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.getItemByBarcode(barcode)?.toDomainModel()
        }
        return inventoryDao.getItemByBarcode(barcode)?.toDomainModel()
    }

    override suspend fun getUsableItemsByCanonicalIngredientId(ingredientId: String): List<InventoryItem> {
        if (isFollower()) {
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower read")
            return dao.getUsableItemsByCanonicalIngredientId(ingredientId).map { it.toDomainModel() }
        }
        return inventoryDao.getUsableItemsByCanonicalIngredientId(ingredientId).map { it.toDomainModel() }
    }

    override suspend fun upsertItem(item: InventoryItem) {
        if (isFollower()) {
            val engine = followerLocalSyncEngine ?: throw IllegalStateException("Follower local sync engine unavailable for follower mutation")
            val deviceId = followerIdentityStore?.getOrCreateDeviceId() ?: throw IllegalStateException("Follower identity store unavailable for follower mutation")
            val dao = followerDatabase?.followerDao() ?: throw IllegalStateException("Follower database unavailable for follower mutation")

            val existing = dao.getItemById(item.id)
            if (existing == null) {
                engine.recordLocalAddItem(
                    deviceId = deviceId,
                    itemId = item.id,
                    canonicalName = item.canonicalName,
                    displayName = item.displayName,
                    category = item.category.name,
                    storageLocation = item.storageLocation.name,
                    quantity = item.quantity,
                    quantityUnit = item.quantityUnit?.name,
                    barcode = item.barcode,
                    notes = item.notes
                )
            } else {
                val isMetadataChanged = existing.displayName != item.displayName ||
                        existing.category != item.category.name ||
                        existing.storageLocation != item.storageLocation.name ||
                        existing.notes != item.notes

                val isQuantityChanged = existing.quantity != item.quantity && item.quantity != null

                if (isMetadataChanged) {
                    engine.recordLocalEditItem(
                        deviceId = deviceId,
                        targetItemId = item.id,
                        displayName = item.displayName,
                        category = item.category.name,
                        storageLocation = item.storageLocation.name,
                        notes = item.notes
                    )
                }

                if (isQuantityChanged) {
                    engine.recordLocalSetQuantity(
                        deviceId = deviceId,
                        targetItemId = item.id,
                        absoluteQuantity = item.quantity!!
                    )
                }
            }
            return
        }
        inventoryDao.insertItem(item.toEntity())
    }

    override suspend fun deleteItem(id: String) {
        if (isFollower()) {
            val engine = followerLocalSyncEngine ?: throw IllegalStateException("Follower local sync engine unavailable for follower mutation")
            val deviceId = followerIdentityStore?.getOrCreateDeviceId() ?: throw IllegalStateException("Follower identity store unavailable for follower mutation")
            engine.recordLocalRequestDiscard(
                deviceId = deviceId,
                targetItemId = id,
                reason = "User requested deletion on Follower"
            )
            return
        }
        inventoryDao.deleteItemById(id)
    }
}
