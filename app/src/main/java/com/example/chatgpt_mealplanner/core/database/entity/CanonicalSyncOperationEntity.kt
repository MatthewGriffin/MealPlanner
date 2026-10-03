package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "canonical_sync_operations",
    indices = [
        Index(value = ["deviceId", "deviceSequence"], unique = true),
        Index(value = ["canonicalSequence"], unique = true)
    ]
)
data class CanonicalSyncOperationEntity(
    @PrimaryKey val operationId: String,
    val deviceId: String,
    val deviceSequence: Long,
    val protocolVersion: Int,
    val operationType: String,
    val targetItemId: String? = null,
    val baseRevision: Long? = null,
    val payloadJson: String,
    val createdAt: Long,
    val canonicalSequence: Long,
    val receivedAt: Long,
    val status: String,
    val resultCode: String,
    val resultPayloadJson: String? = null
)
