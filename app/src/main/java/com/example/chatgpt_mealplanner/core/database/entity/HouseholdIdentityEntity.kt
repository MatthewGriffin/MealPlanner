package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "household_identity")
data class HouseholdIdentityEntity(
    @PrimaryKey val householdId: String,
    val householdName: String,
    val mainDeviceId: String,
    val createdAt: Long
)
