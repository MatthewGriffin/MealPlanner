package com.example.chatgpt_mealplanner.core.sync.follower.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "follower_sync_metadata")
data class FollowerSyncMetadataEntity(
    @PrimaryKey val singletonId: Int = 1,
    val lastReconciledCanonicalSequence: Long = 0L,
    val lastSuccessfulPullAt: Long? = null
)
