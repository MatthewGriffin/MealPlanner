package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    @Query("SELECT * FROM inventory_items ORDER BY displayName ASC")
    fun getAllItems(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items ORDER BY displayName ASC")
    suspend fun getAllItemsSync(): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE status IN ('AVAILABLE', 'LOW', 'USE_SOON') ORDER BY displayName ASC")
    fun getUsableItems(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items WHERE storageLocation = :location AND status IN ('AVAILABLE', 'LOW', 'USE_SOON') ORDER BY displayName ASC")
    fun getUsableItemsByLocation(location: StorageLocation): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items WHERE status = 'LOW' ORDER BY displayName ASC")
    fun getLowStockItems(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items WHERE status = 'USE_SOON' ORDER BY displayName ASC")
    fun getUseSoonItems(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items WHERE status = 'OUT' ORDER BY displayName ASC")
    fun getOutOfStockItems(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items WHERE storageLocation = :location ORDER BY displayName ASC")
    fun getItemsByLocation(location: StorageLocation): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items WHERE status = :status ORDER BY displayName ASC")
    fun getItemsByStatus(status: ItemStatus): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items WHERE id = :id")
    suspend fun getItemById(id: String): InventoryItemEntity?

    @Query("SELECT * FROM inventory_items WHERE barcode = :barcode AND status IN ('AVAILABLE', 'LOW', 'USE_SOON') ORDER BY createdAt DESC")
    suspend fun getItemByBarcode(barcode: String): InventoryItemEntity?

    @Query("SELECT * FROM inventory_items WHERE canonicalIngredientId = :ingredientId AND status IN ('AVAILABLE', 'LOW', 'USE_SOON') ORDER BY openedDate ASC, useByDate ASC")
    suspend fun getUsableItemsByCanonicalIngredientId(ingredientId: String): List<InventoryItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: InventoryItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<InventoryItemEntity>)

    @Update
    suspend fun updateItem(item: InventoryItemEntity)

    @Delete
    suspend fun deleteItem(item: InventoryItemEntity)

    @Query("DELETE FROM inventory_items WHERE id = :id")
    suspend fun deleteItemById(id: String)

    @Query("DELETE FROM inventory_items")
    suspend fun deleteAllItems()
}
