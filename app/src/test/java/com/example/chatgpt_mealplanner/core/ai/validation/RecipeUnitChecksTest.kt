package com.example.chatgpt_mealplanner.core.ai.validation

import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.parseQuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeUnitChecksTest {

    private fun warningsFor(name: String, quantity: Double, unit: String): List<String> =
        RecipeOfferChecks.warnings(
            MealProposal(title = "Dish", ingredients = listOf(ProposedIngredient(displayName = name, quantity = quantity, unit = unit)), reasoningSummary = "Test"),
            "Use the $name."
        )

    private fun queriesFor(name: String, quantity: Double, unit: String): List<String> =
        RecipeOfferChecks.queries(
            MealProposal(title = "Dish", ingredients = listOf(ProposedIngredient(displayName = name, quantity = quantity, unit = unit)), reasoningSummary = "Test"),
            "Use the $name."
        )

    private fun List<String>.mentionsUnit() = any { "as a count" in it || "can't read" in it }

    @Test
    fun aUnitTheAppCantRead_isQueried() {
        val queries = queriesFor("Mustard", 1.0, "tsp")
        val warnings = warningsFor("Mustard", 1.0, "tsp")

        assertTrue(queries.toString(), queries.any { "'tsp'" in it && "GRAM" in it })
        assertEquals(listOf("The app can't read the amount of 'Mustard' (1 tsp), so it will be saved as a count. Check it before you shop."), warnings)
    }

    @Test
    fun aNeverCountedFood_isAskedInUnitsButWarnedInPlainWords() {
        assertTrue(queriesFor("Salt", 6.0, "UNIT").any { "Use GRAM or MILLILITRE" in it })
        assertEquals(
            listOf("'Salt' is given as a count (6) instead of a weight or volume. Check it before you shop."),
            warningsFor("Salt", 6.0, "UNIT")
        )
    }

    @Test
    fun aMissingUnit_isQueried() {
        assertTrue(warningsFor("Mustard", 1.0, "").mentionsUnit())
    }

    @Test
    fun whatGeminiHasReallySentAsACount_isQueried() {
        assertTrue(warningsFor("Salt", 6.0, "UNIT").mentionsUnit())
        assertTrue(warningsFor("Ground cumin", 6.0, "UNIT").mentionsUnit())
        assertTrue(warningsFor("Olive oil", 15.0, "UNIT").mentionsUnit())
    }

    @Test
    fun thingsThatReallyAreCounted_areNotQueried() {
        listOf("Eggs", "Onion", "Garlic cloves", "Stock cube", "Bay leaf", "Red pepper", "Tuna in oil", "Salted butter").forEach { name ->
            assertFalse(name, warningsFor(name, 2.0, "UNIT").mentionsUnit())
        }
    }

    @Test
    fun theSameFoodsByWeightOrInAliases_areNotQueried() {
        assertFalse(warningsFor("Salt", 6.0, "GRAM").mentionsUnit())
        assertFalse(warningsFor("Olive oil", 15.0, "ml").mentionsUnit())
        assertFalse(warningsFor("Eggs", 2.0, "items").mentionsUnit())
    }

    @Test
    fun storedDataStillReadsAsBefore() {
        assertEquals(QuantityUnit.UNIT, parseQuantityUnit(null))
        assertEquals(QuantityUnit.UNIT, parseQuantityUnit("tsp"))
        assertEquals(QuantityUnit.GRAM, parseQuantityUnit(" grams "))
        QuantityUnit.entries.forEach { assertEquals(it, parseQuantityUnit(it.name)) }
    }

    @Test
    fun amountsAreJudgedAgainstThePortionsTheRecipeMakes() {
        fun warnings(servings: Int, batch: Int) = RecipeOfferChecks.warnings(
            MealProposal(
                title = "Traybake", servings = servings, batchPortions = batch,
                ingredients = listOf(ProposedIngredient(displayName = "Chicken drumsticks and thighs", quantity = 1200.0, unit = "GRAM")),
                reasoningSummary = "Test"
            ),
            "Roast the chicken drumsticks and thighs."
        )
        assertTrue(warnings(1, 1).any { "a lot" in it })
        assertTrue(warnings(1, 6).toString(), warnings(1, 6).none { "a lot" in it || "too much" in it })
        assertTrue(warnings(6, 6).none { "a lot" in it })
    }
}
