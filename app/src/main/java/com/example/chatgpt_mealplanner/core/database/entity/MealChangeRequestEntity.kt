package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "meal_change_requests",
    foreignKeys = [
        ForeignKey(
            entity = MealEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mealId"]),
        Index(value = ["authorDeviceId"])
    ]
)
data class MealChangeRequestEntity(
    @PrimaryKey val id: String,
    val mealId: String,
    val authorDeviceId: String,
    val requestText: String,
    val status: String, // PENDING, ACCEPTED, DECLINED, DISCUSSED
    val mainResponse: String?,
    val createdAt: Long,
    val updatedAt: Long
)

fun MealChangeRequestEntity.toSyncDto() =
    com.example.chatgpt_mealplanner.sync.contract.MealChangeRequestSyncDto(
        id, mealId, authorDeviceId, requestText, status, mainResponse, createdAt, updatedAt
    )
