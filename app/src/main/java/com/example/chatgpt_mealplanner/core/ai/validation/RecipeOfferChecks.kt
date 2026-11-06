package com.example.chatgpt_mealplanner.core.ai.validation

import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient

/**
 * Checks a recipe while Gemini can still fix it. A problem stops the card. A query only tells
 * Gemini, because a check on words can be wrong about a correct recipe and the user shouldn't lose
 * the card over it.
 */
object RecipeOfferChecks {
    private val vagueIngredientNames = setOf(
        "herb", "herbs", "spice", "spices", "seasoning", "seasonings",
        "vegetable", "vegetables", "greens", "meat", "protein",
        "sauce", "oil", "garnish", "garnishes", "toppings",
        "herbs to taste", "spices to taste", "seasoning to taste"
    )

    private val describingWords = setOf(
        "fresh", "dried", "ground", "large", "medium", "small", "mature", "chopped", "diced", "sliced",
        "whole", "tinned", "canned", "frozen", "extra", "virgin", "plain", "skinless", "boneless", "free", "range"
    )

    private const val MOST_GRAMS_OR_ML_PER_SERVING = 1000.0

    fun isVague(ingredientName: String): Boolean {
        val normalized = ingredientName.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return normalized in vagueIngredientNames
    }

    fun problems(proposal: MealProposal): List<String> = proposal.ingredients.flatMap { ingredient ->
        listOfNotNull(
            "Ingredient '${ingredient.displayName}' is too vague to shop for. Name the specific ingredient or blend."
                .takeIf { isVague(ingredient.displayName) },
            "Ingredient '${ingredient.displayName}' needs a quantity greater than 0."
                .takeIf { ingredient.quantity <= 0.0 }
        )
    }

    fun queries(proposal: MealProposal, method: String): List<String> {
        val methodText = method.lowercase()
        val unused = proposal.ingredients
            .filter { !it.optional && !isUsedIn(it.displayName, methodText) }
            .map { "'${it.displayName}' is in the ingredients but not in the method." }
        val oversized = proposal.ingredients
            .filter { gramsOrMl(it)?.let { amount -> amount / proposal.servings.coerceAtLeast(1) > MOST_GRAMS_OR_ML_PER_SERVING } == true }
            .map { "'${it.displayName}' looks like too much for ${proposal.servings} servings (${it.quantity} ${it.unit})." }
        return unused + oversized
    }

    private fun isUsedIn(ingredientName: String, methodText: String): Boolean {
        val words = ingredientName.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 }
        val names = words.filterNot { it in describingWords }.ifEmpty { words }
        if (names.isEmpty() || "water" in names) return true
        return names.any { word -> word.removeSuffix("es").removeSuffix("s") in methodText }
    }

    private fun gramsOrMl(ingredient: ProposedIngredient): Double? = when (ingredient.unit) {
        "GRAM", "MILLILITRE" -> ingredient.quantity
        "KILOGRAM", "LITRE" -> ingredient.quantity * 1000.0
        else -> null
    }
}
