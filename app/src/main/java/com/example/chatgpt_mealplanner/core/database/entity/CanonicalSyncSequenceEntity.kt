package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "canonical_sync_sequence")
data class CanonicalSyncSequenceEntity(
    @PrimaryKey val singletonId: Int = 1,
    val nextSequence: Long = 1L
)
