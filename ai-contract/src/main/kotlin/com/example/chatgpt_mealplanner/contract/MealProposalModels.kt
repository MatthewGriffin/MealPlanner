package com.example.chatgpt_mealplanner.contract

import kotlinx.serialization.Serializable

enum class MealGoal {
    NORMAL, QUICK, LOW_CARB, USE_PERISHABLES, CHEAP, BATCH_COOK, COMFORT_FOOD
}

enum class IngredientAvailability {
    AVAILABLE, PARTIAL, MISSING, UNCERTAIN
}

enum class RecipeValidationStatus {
    VALID,
    VALID_WITH_WARNINGS,
    INVALID
}

@Serializable
data class ProposedIngredient(
    val canonicalIngredientId: String? = null,
    val displayName: String,
    val quantity: Double,
    val unit: String,
    val optional: Boolean = false,
    val substitutionAllowed: Boolean = true
)

@Serializable
data class RecipeStep(
    val order: Int,
    val instruction: String,
    val durationMinutes: Int? = null,
    val equipment: String? = null
)

@Serializable
data class MealProposal(
    val title: String,
    val mealType: String? = null,
    val suggestedDate: String? = null,
    val servings: Int = 2,
    val batchPortions: Int = 2,
    val immediatePortions: Int = 2,
    val leftoverPortions: Int = 0,
    val cookInTwoBatches: Boolean = false,
    val tags: Set<String> = emptySet(),
    val ingredients: List<ProposedIngredient>,
    val steps: List<RecipeStep> = emptyList(),
    val estimatedMinutes: Int? = null,
    val mealGoal: MealGoal? = null,
    val reasoningSummary: String
)

data class ValidatedProposedIngredient(
    val ingredient: ProposedIngredient,
    val availability: IngredientAvailability,
    val ownedQuantity: Double?,
    val ownedUnit: String?
)

data class ValidatedMealProposal(
    val proposal: MealProposal,
    val validatedIngredients: List<ValidatedProposedIngredient>,
    val shoppingNeeded: Boolean,
    val status: RecipeValidationStatus,
    val issues: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

@Serializable
sealed interface AiProposedAction {
    @Serializable
    data class SaveMeal(val proposal: MealProposal) : AiProposedAction
    @Serializable
    data class ConfirmCookedMeal(val mealId: String, val mealTitle: String) : AiProposedAction
    @Serializable
    data class MoveMeal(val mealId: String, val destinationDate: String) : AiProposedAction
    @Serializable
    data class AddToShopping(val missingIngredients: List<ProposedIngredient>) : AiProposedAction
}
