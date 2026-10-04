package com.example.chatgpt_mealplanner.core.sync.follower.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "follower_device_sequence")
data class FollowerDeviceSequenceEntity(
    @PrimaryKey val singletonId: Int = 1,
    val nextSequence: Long = 1L
)
