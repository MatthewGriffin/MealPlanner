package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.contract.AiProposedAction
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ValidatedMealProposal

/**
 * Resolves the proposal to persist, preferring the structured AI action over presentation state.
 *
 * A meal-change request can have a complete SaveMeal action even when the proposal passed by
 * the card is stale or has no ingredients. Conversely, an empty structured action remains
 * authoritative so the caller can reject it instead of silently saving an unrelated proposal.
 */
internal object MealProposalSaveResolver {
    fun resolve(
        uiProposal: MealProposal,
        latestValidatedProposal: ValidatedMealProposal?,
        latestProposedAction: AiProposedAction?
    ): MealProposal {
        return (latestProposedAction as? AiProposedAction.SaveMeal)?.proposal
            ?: latestValidatedProposal?.proposal
            ?: uiProposal
    }
}
