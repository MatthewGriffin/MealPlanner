package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemBarcodeEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryMergeLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryBarcodeDao {

    @Query("SELECT * FROM inventory_item_barcodes WHERE normalizedBarcode = :normalizedBarcode LIMIT 1")
    suspend fun getBarcodeEntity(normalizedBarcode: String): InventoryItemBarcodeEntity?

    @Query("SELECT * FROM inventory_item_barcodes WHERE inventoryItemId = :inventoryItemId ORDER BY addedAt ASC")
    suspend fun getBarcodesForItem(inventoryItemId: String): List<InventoryItemBarcodeEntity>

    @Query("SELECT * FROM inventory_item_barcodes WHERE inventoryItemId = :inventoryItemId ORDER BY addedAt ASC")
    fun observeBarcodesForItem(inventoryItemId: String): Flow<List<InventoryItemBarcodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBarcode(entity: InventoryItemBarcodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBarcodes(entities: List<InventoryItemBarcodeEntity>)

    @Query("DELETE FROM inventory_item_barcodes WHERE id = :id")
    suspend fun deleteBarcode(id: String)

    @Query("UPDATE inventory_item_barcodes SET inventoryItemId = :newInventoryItemId WHERE inventoryItemId = :oldInventoryItemId")
    suspend fun transferBarcodes(oldInventoryItemId: String, newInventoryItemId: String)

    @Query("UPDATE inventory_item_barcodes SET inventoryItemId = :newInventoryItemId WHERE id = :barcodeId")
    suspend fun updateBarcodeOwner(barcodeId: String, newInventoryItemId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMergeLog(log: InventoryMergeLogEntity)

    @Update
    suspend fun updateMergeLog(log: InventoryMergeLogEntity)

    @Query("SELECT * FROM inventory_merge_logs WHERE survivorItemId = :survivorItemId AND unmerged = 0 ORDER BY mergedAt DESC")
    suspend fun getActiveMergeLogsForSurvivor(survivorItemId: String): List<InventoryMergeLogEntity>

    @Query("SELECT * FROM inventory_merge_logs WHERE unmerged = 0")
    suspend fun getActiveMergeLogs(): List<InventoryMergeLogEntity>

    @Query("SELECT * FROM inventory_merge_logs WHERE id = :id LIMIT 1")
    suspend fun getMergeLogById(id: String): InventoryMergeLogEntity?
}
