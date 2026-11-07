package com.example.chatgpt_mealplanner.core.ai.validation

import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto

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
    private const val MOST_MEAT_OR_FISH_GRAMS_PER_SERVING = 250.0
    private const val LEAST_MEAT_OR_FISH_GRAMS_PER_SERVING = 40.0

    private val hobTemperature = Regex("""\b(?:low|medium|high|heat)\b[^.]{0,12}\(?\d{2,3}\s?°c""")
    private val smallWeight = Regex("""\b(?:\d|1[0-4])\s?(?:g|grams?)\b(?!\s*\))""")
    private val unitWord = Regex("""\b\d+\s?units?\b""")
    private val commonIngredients = listOf("salt", "pepper", "butter", "garlic", "sugar", "flour")

    fun isVague(ingredientName: String): Boolean {
        val normalized = ingredientName.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return normalized in vagueIngredientNames
    }

    fun problems(
        proposal: MealProposal,
        preferences: UserPreferencesSummaryDto = UserPreferencesSummaryDto()
    ): List<String> {
        // Gemini has sent the meal type, summary and method steps as ingredients, which would end up on the shopping list.
        val otherFields = (listOf(proposal.mealType, proposal.reasoningSummary) + proposal.steps.map { it.instruction })
            .map { it.orEmpty().trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        return proposal.ingredients.flatMap { ingredient -> ingredientProblems(ingredient, otherFields, preferences) }
    }

    private fun ingredientProblems(
        ingredient: ProposedIngredient,
        otherFields: Set<String>,
        preferences: UserPreferencesSummaryDto
    ): List<String> =
        listOfNotNull(
            "'${ingredient.displayName}' isn't an ingredient. List only food in ingredients, each with its quantity and unit, and put the method in steps."
                .takeIf { ingredient.displayName.trim().lowercase() in otherFields },
            "Ingredient '${ingredient.displayName}' is too vague to shop for. Name the specific ingredient or blend."
                .takeIf { isVague(ingredient.displayName) },
            "Ingredient '${ingredient.displayName}' needs a quantity greater than 0."
                .takeIf { ingredient.quantity <= 0.0 }
        ) + DietaryRules.conflicts(ingredient.displayName, preferences) +
            ingredient.substitute.orEmpty().let { swap -> DietaryRules.conflicts(swap, preferences) }

    fun queries(
        proposal: MealProposal,
        method: String,
        preferences: UserPreferencesSummaryDto = UserPreferencesSummaryDto()
    ): List<String> {
        val methodText = method.lowercase()
        val unused = proposal.ingredients
            .filter { !it.optional && !isUsedIn(it.displayName, methodText) }
            .map { "'${it.displayName}' is in the ingredients but not in the method." }
        val oversized = proposal.ingredients
            .filter { gramsOrMl(it)?.let { amount -> amount / proposal.servings.coerceAtLeast(1) > MOST_GRAMS_OR_ML_PER_SERVING } == true }
            .map { "'${it.displayName}' looks like too much for ${proposal.servings} servings (${it.quantity} ${it.unit})." }
        val disliked = proposal.ingredients
            .filter { DietaryRules.isDisliked(it.displayName, preferences) }
            .map { "'${it.displayName}' is something this household dislikes." }
        return unused + oversized + disliked + cookabilityQueries(proposal, methodText)
    }

    /** Mistakes seen in real Gemini recipes that make a recipe harder to follow. */
    private fun cookabilityQueries(proposal: MealProposal, methodText: String): List<String> {
        val listed = proposal.ingredients.joinToString(" ") { it.displayName.lowercase() }
        val servings = proposal.servings.coerceAtLeast(1)
        return listOfNotNull(
            "Give hob heat as low, medium or high, not a temperature.".takeIf { hobTemperature.containsMatchIn(methodText) },
            "Write small amounts in the method in spoons, not grams.".takeIf { smallWeight.containsMatchIn(methodText) },
            "Write whole items in the method by name, such as '4 eggs', not '4 unit eggs'.".takeIf { unitWord.containsMatchIn(methodText) },
            "The title offers a choice. Name one dish.".takeIf { " or " in proposal.title.lowercase() }
        ) + commonIngredients
            .filter { Regex("\\b$it").containsMatchIn(methodText) && !Regex("\\b$it").containsMatchIn(listed) }
            .map { "The method uses $it, but it isn't in the ingredients." } +
            proposal.ingredients
                .filter { DietaryRules.isMeatOrFish(it.displayName) && (gramsOrMl(it) ?: 0.0) / servings > MOST_MEAT_OR_FISH_GRAMS_PER_SERVING }
                .map { "'${it.displayName}' is a lot for ${proposal.servings} servings (${it.quantity} ${it.unit}). Use a normal portion even if there is more in stock." } +
            proposal.ingredients
                .filter { DietaryRules.isMeatOrFish(it.displayName) && it.unit != "MILLILITRE" && it.unit != "LITRE" }
                .filter { (gramsOrMl(it) ?: Double.MAX_VALUE) / servings < LEAST_MEAT_OR_FISH_GRAMS_PER_SERVING }
                .map { "'${it.displayName}' is very little for ${proposal.servings} servings (${it.quantity} ${it.unit}). If it's the main ingredient, use a normal portion even if there is less in stock." }
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
