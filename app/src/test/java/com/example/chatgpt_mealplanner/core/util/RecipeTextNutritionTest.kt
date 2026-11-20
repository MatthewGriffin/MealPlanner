package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.NutritionPerPortion
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeTextNutritionTest {
    private fun proposal(nutrition: NutritionPerPortion?) = MealProposal(
        title = "Dish", nutrition = nutrition, reasoningSummary = "x",
        ingredients = listOf(ProposedIngredient(displayName = "Rice", quantity = 100.0, unit = "GRAM"))
    )

    @Test fun nutritionIsSavedWithTheMethodAndReadBack() {
        val text = proposal(NutritionPerPortion(calories = 520, carbsG = 45, sugarsG = 6, proteinG = 30, fatG = 18)).savedInstructions()
        val parsed = RecipeTextParser.parse(text)
        assertEquals("520 kcal • Carbs 45 g (sugars 6 g) • Protein 30 g • Fat 18 g", parsed.nutrition)
        assertEquals(emptyList<String>(), parsed.other)
    }

    @Test fun noNutritionMeansNoLine() {
        assertNull(RecipeTextParser.parse(proposal(null).savedInstructions()).nutrition)
    }
}
