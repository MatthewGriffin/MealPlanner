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

    private fun List<String>.mentionsUnit() = any { "can't be counted" in it || "can't read" in it }

    @Test
    fun aUnitTheAppCantRead_isQueried() {
        val warnings = warningsFor("Mustard", 1.0, "tsp")

        assertTrue(warnings.toString(), warnings.any { "'tsp'" in it && "GRAM" in it })
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
}
