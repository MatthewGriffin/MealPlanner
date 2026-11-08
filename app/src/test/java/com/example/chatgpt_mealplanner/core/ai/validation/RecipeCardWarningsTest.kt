package com.example.chatgpt_mealplanner.core.ai.validation

import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card warnings show after Gemini failed to correct them, so they're written for the household, not for Gemini. */
class RecipeCardWarningsTest {

    private val recipe = MealProposal(
        title = "Curry or stew",
        servings = 2,
        ingredients = listOf(
            ProposedIngredient(displayName = "Chicken thighs", quantity = 900.0, unit = "GRAM"),
            ProposedIngredient(displayName = "Beef mince", quantity = 20.0, unit = "GRAM"),
            ProposedIngredient(displayName = "Rice", quantity = 2500.0, unit = "GRAM"),
            ProposedIngredient(displayName = "Salt", quantity = 6.0, unit = "UNIT"),
            ProposedIngredient(displayName = "Mustard", quantity = 1.0, unit = "tsp"),
            ProposedIngredient(displayName = "Coriander", quantity = 10.0, unit = "GRAM")
        ),
        reasoningSummary = "Test"
    )
    private val method = "Fry the chicken thighs and beef mince on medium heat (180°C). Add 5 g salt, the mustard, 4 unit eggs and the rice. Season with pepper."

    @Test
    fun everyQueryHasAHouseholdWarning() {
        val queries = RecipeOfferChecks.queries(recipe, method)
        val warnings = RecipeOfferChecks.warnings(recipe, method)

        assertEquals(queries.size, warnings.size)
        assertTrue(warnings.toString(), warnings.size >= 10)
    }

    @Test
    fun warningsDontReadAsInstructionsToGemini() {
        RecipeOfferChecks.warnings(recipe, method).forEach { warning ->
            listOf("GRAM", "UNIT", "MILLILITRE", "Use one of", "Name one dish", "Write ", "Give hob", ".0 ").forEach { geminiWording ->
                assertFalse("'$warning' has '$geminiWording'", geminiWording in warning)
            }
        }
    }

    @Test
    fun warningsSayWhatIsWrongInPlainWords() {
        val warnings = RecipeOfferChecks.warnings(recipe, method)

        listOf(
            "The method doesn't say when to use 'Coriander'.",
            "2500 g of 'Rice' looks like too much for 2 servings. Check it before you shop.",
            "900 g of 'Chicken thighs' is a lot for 2 servings.",
            "20 g of 'Beef mince' is very little for 2 servings if it's the main ingredient.",
            "The method gives some small amounts in grams, so you may need to weigh them.",
            "The method uses pepper, but it isn't in the ingredients, so it won't be on the shopping list."
        ).forEach { assertTrue(warnings.joinToString("\n"), it in warnings) }
    }
}
