package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val devicePublicKey: String,
    val status: String,
    val pairedAt: Long,
    val lastSyncTimestamp: Long
)
