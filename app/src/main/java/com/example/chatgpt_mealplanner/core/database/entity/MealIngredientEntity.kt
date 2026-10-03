package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.QuantityUnit

@Entity(
    tableName = "meal_ingredients",
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
        Index(value = ["canonicalIngredientId"])
    ]
)
data class MealIngredientEntity(
    @PrimaryKey val id: String,
    val mealId: String,
    val canonicalIngredientId: String?,
    val displayName: String,
    val plannedQuantity: Double,
    val plannedUnit: QuantityUnit,
    val actualQuantity: Double?,
    val actualUnit: QuantityUnit?,
    val optional: Boolean,
    val substitutionAllowed: Boolean,
    val substitutedCanonicalIngredientId: String?,
    val substitutedDisplayName: String?
)

fun MealIngredientEntity.toDomainModel(): MealIngredient = MealIngredient(
    id = id,
    mealId = mealId,
    canonicalIngredientId = canonicalIngredientId,
    displayName = displayName,
    plannedQuantity = plannedQuantity,
    plannedUnit = plannedUnit,
    actualQuantity = actualQuantity,
    actualUnit = actualUnit,
    optional = optional,
    substitutionAllowed = substitutionAllowed,
    substitutedCanonicalIngredientId = substitutedCanonicalIngredientId,
    substitutedDisplayName = substitutedDisplayName
)

fun MealIngredient.toEntity(): MealIngredientEntity = MealIngredientEntity(
    id = id,
    mealId = mealId,
    canonicalIngredientId = canonicalIngredientId,
    displayName = displayName,
    plannedQuantity = plannedQuantity,
    plannedUnit = plannedUnit,
    actualQuantity = actualQuantity,
    actualUnit = actualUnit,
    optional = optional,
    substitutionAllowed = substitutionAllowed,
    substitutedCanonicalIngredientId = substitutedCanonicalIngredientId,
    substitutedDisplayName = substitutedDisplayName
)
