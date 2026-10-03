package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.model.Leftover
import com.example.chatgpt_mealplanner.core.model.LeftoverStatus
import com.example.chatgpt_mealplanner.core.model.StorageLocation

@Entity(
    tableName = "leftovers",
    foreignKeys = [
        ForeignKey(
            entity = MealEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceMealId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["sourceMealId"])
    ]
)
data class LeftoverEntity(
    @PrimaryKey val id: String,
    val sourceMealId: String?,
    val name: String,
    val initialPortions: Int,
    val portions: Int,
    val storageLocation: StorageLocation,
    val status: LeftoverStatus,
    val createdDate: String,
    val consumeByDate: String?,
    val createdAt: Long,
    val updatedAt: Long
)

fun LeftoverEntity.toDomainModel(): Leftover = Leftover(
    id = id,
    sourceMealId = sourceMealId,
    name = name,
    initialPortions = initialPortions,
    portions = portions,
    storageLocation = storageLocation,
    status = status,
    createdDate = createdDate,
    consumeByDate = consumeByDate,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Leftover.toEntity(): LeftoverEntity = LeftoverEntity(
    id = id,
    sourceMealId = sourceMealId,
    name = name,
    initialPortions = initialPortions,
    portions = portions,
    storageLocation = storageLocation,
    status = status,
    createdDate = createdDate,
    consumeByDate = consumeByDate,
    createdAt = createdAt,
    updatedAt = updatedAt
)
