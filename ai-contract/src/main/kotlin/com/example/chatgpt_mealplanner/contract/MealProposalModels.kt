package com.example.chatgpt_mealplanner.contract

import kotlinx.serialization.Serializable

@Serializable
enum class MealGoal {
    NORMAL, QUICK, LOW_CARB, USE_PERISHABLES, CHEAP, BATCH_COOK, COMFORT_FOOD
}

@Serializable
enum class IngredientAvailability {
    AVAILABLE, PARTIAL, MISSING, UNCERTAIN
}

@Serializable
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
    val substitutionAllowed: Boolean = true,
    /** What the cook measures, such as "1 tsp". Quantity stays in units the shopping maths can use. */
    val measure: String? = null,
    val substitute: String? = null
)

@Serializable
data class RecipeStep(
    val order: Int,
    val instruction: String,
    val durationMinutes: Int? = null,
    val equipment: String? = null
)

/** An estimate per portion eaten, in kcal and grams. Any value may be missing. */
@Serializable
data class NutritionPerPortion(
    val calories: Int? = null,
    val carbsG: Int? = null,
    val sugarsG: Int? = null,
    val proteinG: Int? = null,
    val fatG: Int? = null,
    val fibreG: Int? = null
) {
    fun isEmpty() = listOfNotNull(calories, carbsG, sugarsG, proteinG, fatG, fibreG).isEmpty()

    /** One line: "520 kcal • Carbs 45 g (sugars 6 g) • Protein 30 g • Fat 18 g • Fibre 5 g". */
    fun summary(): String = listOfNotNull(
        calories?.let { "$it kcal" },
        carbsG?.let { "Carbs $it g" + (sugarsG?.let { s -> " (sugars $s g)" } ?: "") },
        proteinG?.let { "Protein $it g" },
        fatG?.let { "Fat $it g" },
        fibreG?.let { "Fibre $it g" }
    ).joinToString(" \u2022 ")
}

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
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val equipment: List<String> = emptyList(),
    val mealGoal: MealGoal? = null,
    val reasoningSummary: String,
    val changeSummary: String? = null,
    /** The web recipe this was adapted from, such as "BBC Good Food: https://...". */
    val source: String? = null,
    /** Optional extras worth buying that would lift the dish, each with what it adds. Not on the shopping list. */
    val worthAdding: List<String> = emptyList(),
    /** What happens to portions beyond [servings]: null or PLAN_DAYS puts them on later dinners of the plan, FREEZE freezes them. */
    val extrasPlan: String? = null,
    /** Dinners (YYYY-MM-DD) the user asked the extra portions to go on, instead of the next free days of the plan. */
    val extraDates: List<String> = emptyList(),
    /** Id of an existing planned meal this recipe edits (scaled, changed): saving updates that meal in place. */
    val replacesMealId: String? = null,
    /** Estimated nutrition for one portion, shown on the card and kept with the saved method. */
    val nutrition: NutritionPerPortion? = null
) {
    /** Null when the recipe gave no time, so a card never shows an invented one. */
    fun totalMinutes(): Int? =
        estimatedMinutes ?: listOfNotNull(prepMinutes, cookMinutes).takeIf { it.isNotEmpty() }?.sum()

    fun methodLines(): List<String> = steps.sortedBy { it.order }.map { step ->
        "${step.order}. ${step.instruction}" + (step.durationMinutes?.let { " ($it min)" } ?: "")
    }

    /** Planned meals keep one block of text, so the details that have no column of their own go in it. */
    fun savedInstructions(): String {
        val summary = listOfNotNull(
            prepMinutes?.let { "Prep $it min" },
            cookMinutes?.let { "Cook $it min" },
            equipment.takeIf { it.isNotEmpty() }?.let { "Equipment: ${it.joinToString(", ")}" }
        ).joinToString(" • ")
        val swaps = ingredients.mapNotNull { ingredient ->
            ingredient.substitute?.takeIf { it.isNotBlank() }?.let { "Swap ${ingredient.displayName} for $it" }
        }
        val extras = worthAdding.filter { it.isNotBlank() }.map { "Worth adding: $it" }
        val basedOn = source?.takeIf { it.isNotBlank() }?.let { "Based on: $it" }
        val facts = nutrition?.takeUnless { it.isEmpty() }?.let { "Nutrition per portion (estimate): ${it.summary()}" }
        return (listOfNotNull(summary.ifBlank { null }) + methodLines() + listOfNotNull(facts) + swaps + extras + listOfNotNull(basedOn)).joinToString("\n")
    }
}

@Serializable
data class ValidatedProposedIngredient(
    val ingredient: ProposedIngredient,
    val availability: IngredientAvailability,
    val ownedQuantity: Double?,
    val ownedUnit: String?
)

@Serializable
data class ValidatedMealProposal(
    val proposal: MealProposal,
    val validatedIngredients: List<ValidatedProposedIngredient>,
    val shoppingNeeded: Boolean,
    val status: RecipeValidationStatus,
    val issues: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    fun canBeSaved(): Boolean = status != RecipeValidationStatus.INVALID
}

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


/**
 * Durable chat representation of a proposal. The state is changed to APPROVED
 * on the same chat message after the user accepts it, so history never resurrects
 * an actionable card.
 */
@Serializable
data class ProposalMessagePayload(
    val schemaVersion: Int = 1,
    val kind: String,
    val state: String = "PENDING",
    val meals: List<ValidatedMealProposal> = emptyList(),
    val singleMeal: ValidatedMealProposal? = null,
    val mealChangeRequestId: String? = null
)
