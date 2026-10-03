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
}
