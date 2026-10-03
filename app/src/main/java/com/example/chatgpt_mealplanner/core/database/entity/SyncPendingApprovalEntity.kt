package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_pending_approvals",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalSyncOperationEntity::class,
            parentColumns = ["operationId"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["operationId"], unique = true)
    ]
)
data class SyncPendingApprovalEntity(
    @PrimaryKey val approvalId: String,
    val operationId: String,
    val deviceId: String,
    val targetItemId: String,
    val operationType: String,
    val requestedBaseRevision: Long,
    val currentRevisionAtRequest: Long,
    val currentItemSnapshotJson: String,
    val payloadJson: String,
    val status: String, // "PENDING_APPROVAL", "APPROVED", "REJECTED"
    val createdAt: Long
)
