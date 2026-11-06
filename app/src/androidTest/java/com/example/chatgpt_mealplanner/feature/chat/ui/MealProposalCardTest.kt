package com.example.chatgpt_mealplanner.feature.chat.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.contract.IngredientAvailability
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.contract.RecipeStep
import com.example.chatgpt_mealplanner.contract.RecipeValidationStatus
import com.example.chatgpt_mealplanner.contract.ValidatedMealProposal
import com.example.chatgpt_mealplanner.contract.ValidatedProposedIngredient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealProposalCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val cumin = ProposedIngredient(
        displayName = "Ground cumin", quantity = 2.0, unit = "GRAM", measure = "1 tsp", substitute = "smoked paprika"
    )

    private val recipe = MealProposal(
        title = "Shakshuka",
        ingredients = listOf(cumin),
        steps = listOf(RecipeStep(1, "Fry the cumin until fragrant.", durationMinutes = 2)),
        prepMinutes = 10,
        cookMinutes = 25,
        equipment = listOf("Deep frying pan"),
        reasoningSummary = "Uses your tomatoes."
    )

    private fun show(
        proposal: MealProposal,
        status: RecipeValidationStatus = RecipeValidationStatus.VALID,
        issues: List<String> = emptyList()
    ) {
        composeRule.setContent {
            MealProposalCard(
                validatedProposal = ValidatedMealProposal(
                    proposal = proposal,
                    validatedIngredients = proposal.ingredients.map {
                        ValidatedProposedIngredient(it, IngredientAvailability.MISSING, null, null)
                    },
                    shoppingNeeded = true,
                    status = status,
                    issues = issues
                ),
                onSaveToPlan = {},
                isSaving = false
            )
        }
    }

    @Test
    fun showsTheMethodAndRecipeDetails() {
        show(recipe)

        composeRule.onNodeWithText("Method:").assertExists()
        composeRule.onNodeWithText("1. Fry the cumin until fragrant. (2 min)").assertExists()
        composeRule.onNodeWithText("Servings: 2 • About 35 mins").assertExists()
        composeRule.onNodeWithText("Why this suits you: Uses your tomatoes.").assertExists()
        composeRule.onNodeWithText("You'll need: Deep frying pan").assertExists()
        composeRule.onNodeWithText("• Ground cumin (1 tsp, 2.0 gram) or smoked paprika").assertExists()
        composeRule.onNodeWithText("Save to Plan").assertIsEnabled()
    }

    @Test
    fun doesNotInventATimeOrAReason() {
        show(recipe.copy(prepMinutes = null, cookMinutes = null, reasoningSummary = ""))

        composeRule.onNodeWithText("Servings: 2").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Why this suits you", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun invalidRecipe_showsWhyAndCannotBeSaved() {
        show(recipe, RecipeValidationStatus.INVALID, listOf("Ingredient 'oil' is too vague to shop for."))

        composeRule.onNodeWithText("Can't save yet: Ingredient 'oil' is too vague to shop for.").assertExists()
        composeRule.onNodeWithText("Save to Plan").assertIsNotEnabled()
    }
}
