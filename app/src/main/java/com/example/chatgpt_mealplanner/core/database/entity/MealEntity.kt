package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.MealTag
import com.example.chatgpt_mealplanner.core.model.MealType

@Entity(
    tableName = "meals",
    indices = [
        Index(value = ["date"])
    ]
)
data class MealEntity(
    @PrimaryKey val id: String,
    val date: String,
    val mealType: MealType,
    val title: String,
    val servings: Int,
    val status: MealStatus,
    val batchPortions: Int,
    val immediatePortions: Int,
    val leftoverPortions: Int,
    val cookInTwoBatches: Boolean,
    val tags: Set<MealTag>,
    val instructions: String?,
    val notes: String?,
    val cookedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

fun MealEntity.toDomainModel(): Meal = Meal(
    id = id,
    date = date,
    mealType = mealType,
    title = title,
    servings = servings,
    status = status,
    batchPortions = batchPortions,
    immediatePortions = immediatePortions,
    leftoverPortions = leftoverPortions,
    cookInTwoBatches = cookInTwoBatches,
    tags = tags,
    instructions = instructions,
    notes = notes,
    cookedAt = cookedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Meal.toEntity(): MealEntity = MealEntity(
    id = id,
    date = date,
    mealType = mealType,
    title = title,
    servings = servings,
    status = status,
    batchPortions = batchPortions,
    immediatePortions = immediatePortions,
    leftoverPortions = leftoverPortions,
    cookInTwoBatches = cookInTwoBatches,
    tags = tags,
    instructions = instructions,
    notes = notes,
    cookedAt = cookedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)
