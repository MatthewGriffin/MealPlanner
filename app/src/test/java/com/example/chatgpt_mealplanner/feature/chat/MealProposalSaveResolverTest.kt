package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.contract.AiProposedAction
import com.example.chatgpt_mealplanner.contract.IngredientAvailability
import com.example.chatgpt_mealplanner.contract.MealGoal
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.contract.RecipeValidationStatus
import com.example.chatgpt_mealplanner.contract.ValidatedMealProposal
import com.example.chatgpt_mealplanner.contract.ValidatedProposedIngredient
import org.junit.Assert.assertEquals
import org.junit.Test

class MealProposalSaveResolverTest {

    @Test
    fun structuredAiSaveActionWinsOverEmptyCardProposal() {
        val aiProposal = proposal(
            title = "AI replacement",
            ingredients = listOf(ProposedIngredient(displayName = "Chickpeas", quantity = 400.0, unit = "g"))
        )
        val emptyCardProposal = proposal(title = "Stale card", ingredients = emptyList())

        val resolved = MealProposalSaveResolver.resolve(
            uiProposal = emptyCardProposal,
            latestValidatedProposal = validated(emptyCardProposal),
            latestProposedAction = AiProposedAction.SaveMeal(aiProposal)
        )

        assertEquals(aiProposal, resolved)
        assertEquals(1, resolved.ingredients.size)
    }

    @Test
    fun emptyStructuredAiProposalRemainsAuthoritativeInsteadOfSavingStaleIngredients() {
        val emptyAiProposal = proposal(title = "Incomplete AI proposal", ingredients = emptyList())
        val staleCardProposal = proposal(
            title = "Unrelated previous meal",
            ingredients = listOf(ProposedIngredient(displayName = "Rice", quantity = 100.0, unit = "g"))
        )

        val resolved = MealProposalSaveResolver.resolve(
            uiProposal = staleCardProposal,
            latestValidatedProposal = validated(staleCardProposal),
            latestProposedAction = AiProposedAction.SaveMeal(emptyAiProposal)
        )

        assertEquals(emptyAiProposal, resolved)
        assertEquals(0, resolved.ingredients.size)
    }

    @Test
    fun fallsBackToLatestValidatedProposalWhenNoStructuredSaveActionExists() {
        val validatedProposal = proposal(
            title = "Current proposal",
            ingredients = listOf(ProposedIngredient(displayName = "Lentils", quantity = 200.0, unit = "g"))
        )

        val resolved = MealProposalSaveResolver.resolve(
            uiProposal = proposal(title = "Stale card", ingredients = emptyList()),
            latestValidatedProposal = validated(validatedProposal),
            latestProposedAction = null
        )

        assertEquals(validatedProposal, resolved)
    }

    private fun validated(proposal: MealProposal) = ValidatedMealProposal(
        proposal = proposal,
        validatedIngredients = proposal.ingredients.map {
            ValidatedProposedIngredient(
                ingredient = it,
                availability = IngredientAvailability.UNCERTAIN,
                ownedQuantity = null,
                ownedUnit = null
            )
        },
        shoppingNeeded = false,
        status = RecipeValidationStatus.VALID
    )

    private fun proposal(title: String, ingredients: List<ProposedIngredient>) = MealProposal(
        title = title,
        servings = 2,
        batchPortions = 2,
        immediatePortions = 2,
        leftoverPortions = 0,
        cookInTwoBatches = false,
        tags = emptySet(),
        ingredients = ingredients,
        steps = emptyList(),
        estimatedMinutes = 20,
        mealGoal = MealGoal.NORMAL,
        reasoningSummary = "Test proposal"
    )
}
