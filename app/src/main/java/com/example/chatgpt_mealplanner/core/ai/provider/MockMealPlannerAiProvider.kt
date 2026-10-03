package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.contract.MealGoal
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.contract.RecipeStep
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockMealPlannerAiProvider @Inject constructor() : MealPlannerAiProvider {

    override suspend fun send(request: AiProviderRequest): AiProviderResponse {
        val lastUserMessage = request.messages.lastOrNull { it.role == "user" }?.content?.lowercase() ?: ""

        return when {
            lastUserMessage.contains("dinner") || lastUserMessage.contains("low carb") -> {
                AiProviderResponse.ToolCalls(
                    providerContinuationToken = "SCENARIO_A_ROUND_1",
                    calls = listOf(
                        AiToolCall("call_recent", "get_recent_cooked_meals", "{}"),
                        AiToolCall("call_use_soon", "get_use_soon_items", "{}"),
                        AiToolCall("call_usable", "get_usable_inventory", "{}")
                    )
                )
            }
            lastUserMessage.contains("no mushrooms") || lastUserMessage.contains("mushrooms left") -> {
                AiProviderResponse.ToolCalls(
                    providerContinuationToken = "SCENARIO_B_ROUND_1",
                    calls = listOf(
                        AiToolCall("call_ing_search", "get_inventory_for_ingredient", "{\"searchQuery\":\"mushrooms\"}")
                    )
                )
            }
            lastUserMessage.contains("cooked") -> {
                AiProviderResponse.ToolCalls(
                    providerContinuationToken = "SCENARIO_C_ROUND_1",
                    calls = listOf(
                        AiToolCall("call_upcoming", "get_upcoming_planned_meals", "{}")
                    )
                )
            }
            lastUserMessage.contains("buy") || lastUserMessage.contains("shopping") || lastUserMessage.contains("store") -> {
                AiProviderResponse.ToolCalls(
                    providerContinuationToken = "SCENARIO_D_ROUND_1",
                    calls = listOf(
                        AiToolCall("call_shop_req", "calculate_shopping_requirements", "{}")
                    )
                )
            }
            else -> {
                AiProviderResponse.FinalResponse(
                    text = "I've checked your pantry inventory and meal plan. You have 2 planned meals coming up. What would you like to prepare?"
                )
            }
        }
    }

    override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
        return when (request.providerContinuationToken) {
            "SCENARIO_A_ROUND_1" -> {
                val proposal = MealProposal(
                    title = "Low-Carb Chicken & Broccoli Skillet",
                    mealType = "DINNER",
                    suggestedDate = "2026-10-01",
                    servings = 2,
                    batchPortions = 2,
                    immediatePortions = 2,
                    leftoverPortions = 0,
                    cookInTwoBatches = false,
                    tags = setOf("LOW_CARB", "STIR_FRY"),
                    ingredients = listOf(
                        ProposedIngredient("CHICKEN", "Chicken Breast", 400.0, "GRAM"),
                        ProposedIngredient("BROCCOLI", "Fresh Broccoli", 500.0, "GRAM"),
                        ProposedIngredient("SPINACH", "Fresh Spinach", 100.0, "GRAM")
                    ),
                    steps = listOf(
                        RecipeStep(1, "Dice chicken breast into 2cm cubes.", 5, "Cutting Board"),
                        RecipeStep(2, "Sauté chicken in skillet over medium heat until golden.", 10, "Skillet"),
                        RecipeStep(3, "Add broccoli and spinach, cover and steam 5 mins.", 5, "Skillet")
                    ),
                    estimatedMinutes = 20,
                    mealGoal = MealGoal.LOW_CARB,
                    reasoningSummary = "Uses your chicken, broccoli, and spinach, avoids repeated carbs, and fits your low-carb request."
                )

                AiProviderResponse.FinalResponse(
                    text = "Here is a low-carb dinner proposal using your owned ingredients:",
                    proposal = proposal
                )
            }

            "SCENARIO_B_ROUND_1" -> {
                AiProviderResponse.ToolCalls(
                    providerContinuationToken = "SCENARIO_B_ROUND_2",
                    calls = listOf(
                        AiToolCall("call_out_mush", "mark_all_inventory_for_canonical_ingredient_out", "{\"canonicalIngredientId\":\"MUSHROOM\"}")
                    )
                )
            }

            "SCENARIO_B_ROUND_2" -> {
                AiProviderResponse.FinalResponse(
                    text = "I've updated your inventory: all mushroom stock is now marked out of stock."
                )
            }

            "SCENARIO_C_ROUND_1" -> {
                val upcomingMealsJson = request.toolResults.firstOrNull { it.toolCallId == "call_upcoming" }?.payloadJson ?: ""
                val firstMealId = extractMealIdFromJson(upcomingMealsJson) ?: "m1"

                AiProviderResponse.ToolCalls(
                    providerContinuationToken = "SCENARIO_C_ROUND_2",
                    calls = listOf(
                        AiToolCall("call_prep_cook", "prepare_mark_meal_cooked", "{\"mealId\":\"$firstMealId\"}")
                    )
                )
            }

            "SCENARIO_C_ROUND_2" -> {
                AiProviderResponse.FinalResponse(
                    text = "Please confirm the meal cooking details below:"
                )
            }

            "SCENARIO_D_ROUND_1" -> {
                AiProviderResponse.FinalResponse(
                    text = "Based on your planned meals and pantry stock, your shopping list has been calculated."
                )
            }

            else -> {
                AiProviderResponse.FinalResponse(
                    text = "Done processing your request."
                )
            }
        }
    }

    private fun extractMealIdFromJson(json: String): String? {
        if (json.contains("\"id\":\"")) {
            val start = json.indexOf("\"id\":\"") + 6
            val end = json.indexOf("\"", start)
            if (start in 0 until end) return json.substring(start, end)
        }
        return null
    }
}
