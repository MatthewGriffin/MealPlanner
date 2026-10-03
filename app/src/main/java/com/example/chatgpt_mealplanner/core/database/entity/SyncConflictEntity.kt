package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_conflicts",
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
data class SyncConflictEntity(
    @PrimaryKey val conflictId: String,
    val operationId: String,
    val deviceId: String,
    val targetItemId: String,
    val operationType: String,
    val baseRevision: Long,
    val currentRevision: Long,
    val incomingPayloadJson: String,
    val currentItemSnapshotJson: String,
    val status: String, // "OPEN", "RESOLVED"
    val createdAt: Long,
    val resolvedAt: Long? = null
)
