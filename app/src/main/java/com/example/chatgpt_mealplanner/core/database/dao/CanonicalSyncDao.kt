package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity

@Dao
interface CanonicalSyncDao {

    @Query("SELECT * FROM canonical_sync_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun getByOperationId(operationId: String): CanonicalSyncOperationEntity?

    @Query("SELECT * FROM canonical_sync_operations WHERE deviceId = :deviceId AND deviceSequence = :deviceSequence LIMIT 1")
    suspend fun getByDeviceSequence(deviceId: String, deviceSequence: Long): CanonicalSyncOperationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCanonicalOperation(op: CanonicalSyncOperationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConflict(conflict: SyncConflictEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPendingApproval(approval: SyncPendingApprovalEntity)

    @Query("SELECT * FROM canonical_sync_sequence WHERE singletonId = 1 LIMIT 1")
    suspend fun getSequenceEntity(): CanonicalSyncSequenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSequenceEntity(entity: CanonicalSyncSequenceEntity)

    @Query("SELECT * FROM sync_conflicts WHERE operationId = :operationId LIMIT 1")
    suspend fun getConflictByOperationId(operationId: String): SyncConflictEntity?

    @Query("SELECT * FROM sync_pending_approvals WHERE operationId = :operationId LIMIT 1")
    suspend fun getApprovalByOperationId(operationId: String): SyncPendingApprovalEntity?
}
