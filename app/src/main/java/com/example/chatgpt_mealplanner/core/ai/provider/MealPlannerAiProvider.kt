package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse

interface MealPlannerAiProvider {

    suspend fun send(
        request: AiProviderRequest
    ): AiProviderResponse

    suspend fun continueWithToolResults(
        request: AiContinueWithToolsRequest
    ): AiProviderResponse

    suspend fun testConnection(): AiProviderResponse {
        return AiProviderResponse.FinalResponse("Connected")
    }

    /** Turns a typed or dictated list of what the household owns into stock lines to review. */
    suspend fun parsePantryList(text: String): PantryListResult =
        PantryListResult.Failed("This AI provider can't read a pantry list.")

    /** Picks, for each listed line, the food database product the user most likely means. */
    suspend fun chooseProducts(lines: List<ProductMatchLine>): ProductMatchResult =
        ProductMatchResult.Failed("This AI provider can't match products.")
}
