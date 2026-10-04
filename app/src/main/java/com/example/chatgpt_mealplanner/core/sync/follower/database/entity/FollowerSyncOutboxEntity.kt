package com.example.chatgpt_mealplanner.core.sync.follower.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "follower_sync_outbox",
    indices = [
        Index(value = ["deviceId", "deviceSequence"], unique = true)
    ]
)
data class FollowerSyncOutboxEntity(
    @PrimaryKey val operationId: String,
    val deviceId: String,
    val deviceSequence: Long,
    val protocolVersion: Int,
    val operationType: String,
    val targetItemId: String? = null,
    val baseRevision: Long? = null,
    val payloadJson: String,
    val createdAt: Long,
    val status: String, // "PENDING", "IN_FLIGHT", "APPLIED", "CONFLICT", "PENDING_APPROVAL", "REJECTED"
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastErrorCode: String? = null,
    val mainCanonicalSequence: Long? = null,
    val mainResultCode: String? = null,
    val mainResultingRevision: Long? = null,
    val conflictId: String? = null,
    val approvalId: String? = null,
    val createdLocallyAt: Long,
    val completedAt: Long? = null
)
