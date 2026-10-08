package com.example.chatgpt_mealplanner.core.sync.follower.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity

@Dao
interface FollowerDao {

    @Query("SELECT * FROM follower_device_sequence WHERE singletonId = 1 LIMIT 1")
    suspend fun getSequenceEntity(): FollowerDeviceSequenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSequenceEntity(entity: FollowerDeviceSequenceEntity)

    @Query("SELECT * FROM follower_sync_metadata WHERE singletonId = 1 LIMIT 1")
    suspend fun getSyncMetadata(): FollowerSyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSyncMetadata(metadata: FollowerSyncMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutboxOperation(op: FollowerSyncOutboxEntity)

    @Query("UPDATE follower_sync_outbox SET status = :status, attemptCount = :attemptCount, lastAttemptAt = :lastAttemptAt, lastErrorCode = :lastErrorCode WHERE operationId = :operationId")
    suspend fun updateOutboxStatus(operationId: String, status: String, attemptCount: Int, lastAttemptAt: Long?, lastErrorCode: String?)

    @Query("UPDATE follower_sync_outbox SET status = :status, mainCanonicalSequence = :canonicalSeq, mainResultCode = :resultCode, mainResultingRevision = :resultingRev, conflictId = :conflictId, approvalId = :approvalId, completedAt = :completedAt WHERE operationId = :operationId")
    suspend fun reconcileOutboxResult(operationId: String, status: String, canonicalSeq: Long?, resultCode: String?, resultingRev: Long?, conflictId: String?, approvalId: String?, completedAt: Long?)

    @Query("UPDATE follower_sync_outbox SET status = 'PENDING' WHERE status = 'IN_FLIGHT'")
    suspend fun resetInFlightOpsToPending()

    @Query("SELECT * FROM follower_sync_outbox WHERE status IN ('PENDING', 'IN_FLIGHT') ORDER BY deviceSequence ASC")
    suspend fun getPendingOperationsInOrder(): List<FollowerSyncOutboxEntity>

    @Query("SELECT * FROM follower_sync_outbox ORDER BY deviceSequence ASC")
    suspend fun getAllOutboxOps(): List<FollowerSyncOutboxEntity>

    @Query("SELECT * FROM follower_sync_outbox WHERE targetItemId = :itemId AND status IN ('PENDING', 'IN_FLIGHT', 'CONFLICT', 'PENDING_APPROVAL') ORDER BY deviceSequence ASC")
    suspend fun getUnresolvedOutboxOpsForTargetItem(itemId: String): List<FollowerSyncOutboxEntity>

    @Query("SELECT * FROM follower_sync_outbox WHERE operationId = :operationId LIMIT 1")
    suspend fun getOutboxByOperationId(operationId: String): FollowerSyncOutboxEntity?

    @Query("SELECT * FROM follower_inventory_items WHERE itemId = :itemId LIMIT 1")
    suspend fun getItemById(itemId: String): FollowerInventoryItemEntity?

    @Query("SELECT * FROM follower_inventory_items ORDER BY displayName ASC")
    suspend fun getAllItems(): List<FollowerInventoryItemEntity>

    @Query("DELETE FROM follower_inventory_items")
    suspend fun clearAllInventoryItems() = Unit

    @Query("SELECT COUNT(*) FROM follower_inventory_items WHERE status IN ('AVAILABLE', 'LOW', 'USE_SOON')")
    suspend fun getUsableItemsCount(): Int = 0

    @Query("SELECT COUNT(*) FROM follower_inventory_items WHERE status = 'OUT'")
    suspend fun getOutItemsCount(): Int = 0

    @Query("SELECT * FROM follower_inventory_items ORDER BY displayName ASC")
    fun observeAllItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())

    @Query("SELECT * FROM follower_inventory_items WHERE status IN ('AVAILABLE', 'LOW', 'USE_SOON') ORDER BY displayName ASC")
    fun observeUsableItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())

    @Query("SELECT * FROM follower_inventory_items WHERE storageLocation = :location AND status IN ('AVAILABLE', 'LOW', 'USE_SOON') ORDER BY displayName ASC")
    fun observeUsableItemsByLocation(location: String): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())

    @Query("SELECT * FROM follower_inventory_items WHERE status = 'LOW' ORDER BY displayName ASC")
    fun observeLowStockItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())

    @Query("SELECT * FROM follower_inventory_items WHERE status = 'USE_SOON' ORDER BY displayName ASC")
    fun observeUseSoonItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())

    @Query("SELECT * FROM follower_inventory_items WHERE status = 'OUT' ORDER BY displayName ASC")
    fun observeOutOfStockItems(): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())

    @Query("SELECT * FROM follower_inventory_items WHERE storageLocation = :location ORDER BY displayName ASC")
    fun observeItemsByLocation(location: String): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())

    @Query("SELECT * FROM follower_inventory_items WHERE status = :status ORDER BY displayName ASC")
    fun observeItemsByStatus(status: String): kotlinx.coroutines.flow.Flow<List<FollowerInventoryItemEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())

    @Query("SELECT * FROM follower_inventory_items WHERE barcode = :barcode LIMIT 1")
    suspend fun getItemByBarcode(barcode: String): FollowerInventoryItemEntity? = null

    @Query("SELECT * FROM follower_inventory_items WHERE canonicalName = :canonicalName AND status IN ('AVAILABLE', 'LOW', 'USE_SOON') ORDER BY displayName ASC")
    suspend fun getUsableItemsByCanonicalIngredientId(canonicalName: String): List<FollowerInventoryItemEntity> = emptyList()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateItem(item: FollowerInventoryItemEntity)
}
