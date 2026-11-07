package com.example.chatgpt_mealplanner.feature.mealplanner.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealRating
import com.example.chatgpt_mealplanner.core.model.MealTag
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddEditMealDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val chicken = MealIngredient(
        id = "ingredient-1",
        mealId = "meal-1",
        canonicalIngredientId = "CHICKEN_THIGH",
        displayName = "Chicken thighs",
        plannedQuantity = 400.0,
        plannedUnit = QuantityUnit.GRAM,
        actualQuantity = 380.0,
        actualUnit = QuantityUnit.GRAM,
        optional = true,
        substitutionAllowed = false,
        substitutedCanonicalIngredientId = "TOFU",
        substitutedDisplayName = "Tofu"
    )

    private val savedMeal = Meal(
        id = "meal-1",
        date = "2026-11-10",
        title = "Chicken curry",
        servings = 4,
        batchPortions = 4,
        immediatePortions = 2,
        leftoverPortions = 2,
        cookInTwoBatches = true,
        tags = setOf(MealTag.CURRY),
        instructions = "1. Fry the onions.\n2. Add the chicken.",
        notes = "Mild for the children",
        rating = MealRating.UP,
        ratedAt = 3_000L,
        createdAt = 1_000L,
        updatedAt = 2_000L
    )

    private var saved: Pair<Meal, List<MealIngredient>>? = null

    private fun showDialog(existing: MealWithIngredients?) {
        composeRule.setContent {
            AddEditMealDialog(
                mealWithIngredients = existing,
                selectedDate = "2026-11-10",
                onDismiss = {},
                onSaveMeal = { meal, ingredients -> saved = meal to ingredients }
            )
        }
    }

    private fun field(label: String) = composeRule.onNode(hasText(label) and hasSetTextAction())

    private fun save(): Pair<Meal, List<MealIngredient>> {
        composeRule.onNodeWithText("Save Meal").performClick()
        composeRule.waitForIdle()
        return checkNotNull(saved) { "The dialog did not save" }
    }

    @Test
    fun editingAMealKeepsTheDetailsTheDialogDoesNotShow() {
        showDialog(MealWithIngredients(savedMeal, listOf(chicken)))

        field("Meal Title").performTextReplacement("Chicken korma")
        val (meal, _) = save()

        assertEquals("Chicken korma", meal.title)
        assertEquals(savedMeal.instructions, meal.instructions)
        assertEquals(savedMeal.notes, meal.notes)
        assertEquals(savedMeal.tags, meal.tags)
        assertTrue(meal.cookInTwoBatches)
        assertEquals("meal-1", meal.id)
        assertEquals(1_000L, meal.createdAt)
        assertEquals(MealRating.UP, meal.rating)
        assertEquals(3_000L, meal.ratedAt)
    }

    @Test
    fun editingAMealKeepsTheIngredientDetailsTheDialogDoesNotShow() {
        showDialog(MealWithIngredients(savedMeal, listOf(chicken)))

        val (_, ingredients) = save()

        assertEquals(listOf(chicken), ingredients)
    }

    @Test
    fun typedIngredientNameAndQuantityAppearAndAreSaved() {
        showDialog(existing = null)

        field("Meal Title").performTextInput("Rice bowl")
        field("Name").performTextInput("Rice")
        field("Qty").performTextReplacement("250")

        composeRule.onNode(hasText("Rice") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onNode(hasText("250") and hasSetTextAction()).assertIsDisplayed()

        val (meal, ingredients) = save()
        val rice = ingredients.single()
        assertEquals("Rice", rice.displayName)
        assertEquals(250.0, rice.plannedQuantity, 0.0)
        assertEquals("RICE", rice.canonicalIngredientId)
        assertEquals(meal.id, rice.mealId)
        assertTrue(meal.id.isNotBlank())
    }

    @Test
    fun tickingOptionalIsShownAndSaved() {
        showDialog(MealWithIngredients(savedMeal, listOf(chicken.copy(optional = false))))

        composeRule.onNode(isToggleable()).performClick()

        composeRule.onNode(isToggleable()).assert(isOn())
        assertTrue(save().second.single().optional)
    }

    @Test
    fun renamingAnIngredientStopsItMatchingTheOldStockItem() {
        showDialog(MealWithIngredients(savedMeal, listOf(chicken)))

        field("Chicken thighs").performTextReplacement("Red onion")
        val renamed = save().second.single()

        assertEquals("Red onion", renamed.displayName)
        assertEquals("RED_ONION", renamed.canonicalIngredientId)
    }
}
