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

    @Query("SELECT * FROM follower_sync_outbox WHERE targetItemId = :itemId AND status IN ('PENDING', 'IN_FLIGHT') ORDER BY deviceSequence ASC")
    suspend fun getUnresolvedOutboxOpsForTargetItem(itemId: String): List<FollowerSyncOutboxEntity>

    @Query("SELECT * FROM follower_sync_outbox WHERE operationId = :operationId LIMIT 1")
    suspend fun getOutboxByOperationId(operationId: String): FollowerSyncOutboxEntity?

    @Query("SELECT * FROM follower_inventory_items WHERE itemId = :itemId LIMIT 1")
    suspend fun getItemById(itemId: String): FollowerInventoryItemEntity?

    @Query("SELECT * FROM follower_inventory_items ORDER BY displayName ASC")
    suspend fun getAllItems(): List<FollowerInventoryItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateItem(item: FollowerInventoryItemEntity)
}
