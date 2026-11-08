package com.example.chatgpt_mealplanner.core.ai.validation

import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.quantityUnitOrNull
import com.example.chatgpt_mealplanner.core.util.formatQuantityAndUnit

/**
 * Checks a recipe while Gemini can still fix it. A problem stops the card. A query goes to Gemini
 * and, if it's still there after the corrections, shows as a warning. A check on words can be wrong
 * about a correct recipe, so the user shouldn't lose the card over it.
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

    // Gemini has sent "Salt: 6 UNIT" and "Olive oil: 15 UNIT". The name has to end with the word, so "Salted butter" can be counted.
    private val neverCounted = Regex("""\b(?:salt|black pepper|white pepper|cumin|paprika|turmeric|cinnamon|oil|flour|sugar|water)$""")

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
    ): List<String> = findings(proposal, method).map { it.forGemini } + proposal.ingredients
        .filter { DietaryRules.isDisliked(it.displayName, preferences) }
        .map { "'${it.displayName}' is something this household dislikes." }

    /**
     * The same findings as [queries] without the household's dislikes, worded for the card. They show
     * only after Gemini failed to correct them, so they tell the household what's wrong, not how to write it.
     */
    fun warnings(proposal: MealProposal, method: String): List<String> = findings(proposal, method).map { it.forHousehold }

    /** One finding, worded once as an instruction for Gemini and once as a note for the household. */
    private class Finding(val forGemini: String, val forHousehold: String)

    private fun findings(proposal: MealProposal, method: String): List<Finding> {
        val methodText = method.lowercase()
        val servings = servingsText(proposal.servings)
        val unused = proposal.ingredients
            .filter { !it.optional && !isUsedIn(it.displayName, methodText) }
            .map {
                Finding(
                    "'${it.displayName}' is in the ingredients but not in the method.",
                    "The method doesn't say when to use '${it.displayName}'."
                )
            }
        val oversized = proposal.ingredients
            .filter { gramsOrMl(it)?.let { amount -> amount / proposal.servings.coerceAtLeast(1) > MOST_GRAMS_OR_ML_PER_SERVING } == true }
            .map {
                Finding(
                    "'${it.displayName}' looks like too much for ${proposal.servings} servings (${it.quantity} ${it.unit}).",
                    "${amountOf(it)} of '${it.displayName}' looks like too much for $servings. Check it before you shop."
                )
            }
        return unused + oversized + unitFindings(proposal) + cookabilityFindings(proposal, methodText)
    }

    /** An unknown unit is saved as a count, so these amounts would reach the shopping list wrong. */
    private fun unitFindings(proposal: MealProposal): List<Finding> = proposal.ingredients.mapNotNull { ingredient ->
        val name = ingredient.displayName
        when (quantityUnitOrNull(ingredient.unit)) {
            null -> Finding(
                "'$name' has the unit '${ingredient.unit}', which the app can't read. Use one of ${QuantityUnit.entries.joinToString()}.",
                "The app can't read the amount of '$name' (${amountOf(ingredient)}), so it will be saved as a count. Check it before you shop."
            )
            QuantityUnit.UNIT -> Finding(
                "'$name' can't be counted (${ingredient.quantity} ${ingredient.unit}). Use GRAM or MILLILITRE.",
                "'$name' is given as a count (${amountOf(ingredient)}) instead of a weight or volume. Check it before you shop."
            ).takeIf { name.trim().lowercase().let { neverCounted.containsMatchIn(it) && " in " !in it } }
            else -> null
        }
    }

    /** Mistakes seen in real Gemini recipes that make a recipe harder to follow. */
    private fun cookabilityFindings(proposal: MealProposal, methodText: String): List<Finding> {
        val listed = proposal.ingredients.joinToString(" ") { it.displayName.lowercase() }
        val servings = proposal.servings.coerceAtLeast(1)
        val servingsText = servingsText(proposal.servings)
        return listOfNotNull(
            Finding(
                "Give hob heat as low, medium or high, not a temperature.",
                "The method gives hob heat as a temperature. Most hobs are set to low, medium or high."
            ).takeIf { hobTemperature.containsMatchIn(methodText) },
            Finding(
                "Write small amounts in the method in spoons, not grams.",
                "The method gives some small amounts in grams, so you may need to weigh them."
            ).takeIf { smallWeight.containsMatchIn(methodText) },
            Finding(
                "Write whole items in the method by name, such as '4 eggs', not '4 unit eggs'.",
                "Where the method says something like '4 unit eggs', it means 4 eggs."
            ).takeIf { unitWord.containsMatchIn(methodText) },
            Finding(
                "The title offers a choice. Name one dish.",
                "The title offers a choice of dishes, but the recipe may only make one."
            ).takeIf { " or " in proposal.title.lowercase() }
        ) + commonIngredients
            .filter { Regex("\\b$it").containsMatchIn(methodText) && !Regex("\\b$it").containsMatchIn(listed) }
            .map {
                Finding(
                    "The method uses $it, but it isn't in the ingredients.",
                    "The method uses $it, but it isn't in the ingredients, so it won't be on the shopping list."
                )
            } +
            proposal.ingredients
                .filter { DietaryRules.isMeatOrFish(it.displayName) && (gramsOrMl(it) ?: 0.0) / servings > MOST_MEAT_OR_FISH_GRAMS_PER_SERVING }
                .map {
                    Finding(
                        "'${it.displayName}' is a lot for ${proposal.servings} servings (${it.quantity} ${it.unit}). Use a normal portion even if there is more in stock.",
                        "${amountOf(it)} of '${it.displayName}' is a lot for $servingsText."
                    )
                } +
            proposal.ingredients
                .filter { DietaryRules.isMeatOrFish(it.displayName) && it.unit != "MILLILITRE" && it.unit != "LITRE" }
                .filter { (gramsOrMl(it) ?: Double.MAX_VALUE) / servings < LEAST_MEAT_OR_FISH_GRAMS_PER_SERVING }
                .map {
                    Finding(
                        "'${it.displayName}' is very little for ${proposal.servings} servings (${it.quantity} ${it.unit}). If it's the main ingredient, use a normal portion even if there is less in stock.",
                        "${amountOf(it)} of '${it.displayName}' is very little for $servingsText if it's the main ingredient."
                    )
                }
    }

    private fun servingsText(servings: Int) = if (servings == 1) "1 serving" else "$servings servings"

    /** The amount as the household reads it, such as "6 g" or "2 tsp", not "6.0 GRAM". */
    private fun amountOf(ingredient: ProposedIngredient): String =
        quantityUnitOrNull(ingredient.unit)?.let { formatQuantityAndUnit(ingredient.quantity, it) }
            ?: listOf(formatQuantityAndUnit(ingredient.quantity, null), ingredient.unit.trim()).filter { it.isNotEmpty() }.joinToString(" ")

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
