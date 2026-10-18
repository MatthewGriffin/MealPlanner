package com.example.chatgpt_mealplanner.sync.contract

import kotlinx.serialization.Serializable

@Serializable
data class MealSyncDto(
    val id: String,
    val date: String, // ISO-8601 Date (YYYY-MM-DD)
    val mealType: String = "DINNER",
    val title: String,
    val servings: Int = 2,
    val status: String = "PLANNED",
    val batchPortions: Int = 2,
    val immediatePortions: Int = 2,
    val leftoverPortions: Int = 0,
    val cookInTwoBatches: Boolean = false,
    val tags: Set<String> = emptySet(),
    val instructions: String? = null,
    val notes: String? = null,
    val cookedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class MealIngredientSyncDto(
    val id: String,
    val mealId: String,
    val canonicalIngredientId: String? = null,
    val displayName: String,
    val plannedQuantity: Double,
    val plannedUnit: String,
    val actualQuantity: Double? = null,
    val actualUnit: String? = null,
    val optional: Boolean = false,
    val substitutionAllowed: Boolean = true,
    val substitutedCanonicalIngredientId: String? = null,
    val substitutedDisplayName: String? = null
)

@Serializable
data class MealWithIngredientsSyncDto(
    val meal: MealSyncDto,
    val ingredients: List<MealIngredientSyncDto>
)

