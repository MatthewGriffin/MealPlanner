package com.example.chatgpt_mealplanner.core.sync.follower.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import com.example.chatgpt_mealplanner.sync.contract.MealChangeRequestSyncDto

// Deliberately no FK to AppDatabase: this projection and its feed cursor share
// FollowerDatabase, even when the referenced meal is not stored locally.
@Entity(tableName = "follower_meal_requests")
data class FollowerMealRequestEntity(
    @PrimaryKey val id: String,
    val mealId: String,
    val authorDeviceId: String,
    val requestText: String,
    val status: String,
    val mainResponse: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val canonicalSequence: Long
) {
    fun toRequest() = MealChangeRequestEntity(
        id, mealId, authorDeviceId, requestText, status, mainResponse, createdAt, updatedAt
    )
}

fun MealChangeRequestSyncDto.toFollowerEntity(sequence: Long) = FollowerMealRequestEntity(
    id, mealId, authorDeviceId, requestText, status, mainResponse, createdAt, updatedAt, sequence
)
