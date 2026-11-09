package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.contract.RecipeStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MEAL_PLAN_MARKER = "MEAL_PLAN_JSON:"

/** Reads the meal plan or recipe the model writes into a text reply, and hides the JSON from the user. */
internal object ModelReplyText {

    private val json = Json { ignoreUnknownKeys = true }
    private val fencedJsonArray = Regex("""```(?:json)?\s*(\[[\s\S]*])\s*(?:```|$)""")

    fun mealPlan(text: String): List<MealProposal>? {
        val planJson = mealPlanJsonIn(text) ?: return null
        return try {
            val array = json.parseToJsonElement(planJson).jsonArray
            val meals = array.map { element ->
                val obj = element.jsonObject
                MealProposal(
                    title = obj["title"]?.jsonPrimitive?.content ?: return null,
                    mealType = obj["mealType"]?.jsonPrimitive?.content,
                    suggestedDate = obj["suggestedDate"]?.jsonPrimitive?.content ?: obj["date"]?.jsonPrimitive?.content,
                    servings = obj["servings"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2,
                    ingredients = obj["ingredients"]?.jsonArray?.map { ingredientElement ->
                        val ingredient = ingredientElement.jsonObject
                        ProposedIngredient(
                            displayName = ingredient["displayName"]?.jsonPrimitive?.content ?: return null,
                            quantity = ingredient["quantity"]?.jsonPrimitive?.content?.toDoubleOrNull()
                                ?: ingredient["plannedQuantity"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null,
                            unit = ingredient["unit"]?.jsonPrimitive?.content
                                ?: ingredient["plannedUnit"]?.jsonPrimitive?.content.orEmpty(),
                            optional = ingredient["optional"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                            substitutionAllowed = ingredient["substitutionAllowed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                            measure = ingredient["measure"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                            substitute = ingredient["substitute"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                        )
                    } ?: emptyList(),
                    steps = obj["steps"]?.jsonArray?.mapIndexed { index, stepElement ->
                        val step = stepElement.jsonObject
                        RecipeStep(
                            order = step["order"]?.jsonPrimitive?.content?.toIntOrNull() ?: index + 1,
                            instruction = step["instruction"]?.jsonPrimitive?.content ?: "",
                            durationMinutes = step["durationMinutes"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt()
                        )
                    } ?: emptyList(),
                    prepMinutes = obj["prepMinutes"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt(),
                    cookMinutes = obj["cookMinutes"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt(),
                    reasoningSummary = obj["whyThisSuits"]?.jsonPrimitive?.contentOrNull
                        ?: obj["reasoningSummary"]?.jsonPrimitive?.contentOrNull
                        ?: "Part of the proposed weekly plan"
                )
            }
            meals.takeIf { it.size > 1 && it.all { meal -> meal.title.isNotBlank() && meal.suggestedDate != null && meal.ingredients.isNotEmpty() && meal.steps.isNotEmpty() } }
        } catch (_: Throwable) {
            null
        }
    }

    /** Models don't always use the marker, and sometimes send only a code fence. */
    private fun mealPlanJsonIn(text: String): String? =
        if (text.contains(MEAL_PLAN_MARKER)) {
            text.substringAfter(MEAL_PLAN_MARKER).trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        } else {
            fencedJsonArray.find(text)?.groupValues?.get(1)
        }

    /** The JSON is for the app. If it can't be read, the user still shouldn't see it. */
    fun withoutMealPlanJson(text: String, planWasRead: Boolean): String {
        val withoutJson = when {
            text.contains(MEAL_PLAN_MARKER) -> text.substringBefore(MEAL_PLAN_MARKER)
            planWasRead -> fencedJsonArray.replace(text, "")
            else -> text
        }
        return withoutJson.trim().removeSuffix("***").trim()
    }

    fun mealProposal(text: String): MealProposal? {
        if (!text.contains("PROPOSAL_JSON:")) return null
        return try {
            val jsonPart = text.substringAfter("PROPOSAL_JSON:").substringBefore("\n\n").trim()
            val parsedObj = json.parseToJsonElement(jsonPart).jsonObject
            MealProposal(
                title = parsedObj["title"]?.jsonPrimitive?.content ?: "Proposed Meal",
                mealType = parsedObj["mealType"]?.jsonPrimitive?.content,
                suggestedDate = parsedObj["date"]?.jsonPrimitive?.content,
                servings = parsedObj["servings"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2,
                ingredients = parsedObj["ingredients"]?.jsonArray?.map { ingEl ->
                    val ingObj = ingEl.jsonObject
                    ProposedIngredient(
                        canonicalIngredientId = ingObj["canonicalIngredientId"]?.jsonPrimitive?.content,
                        displayName = ingObj["displayName"]?.jsonPrimitive?.content ?: "Ingredient",
                        quantity = ingObj["plannedQuantity"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0,
                        unit = ingObj["plannedUnit"]?.jsonPrimitive?.content ?: "GRAM"
                    )
                } ?: emptyList(),
                reasoningSummary = parsedObj["reasoningSummary"]?.jsonPrimitive?.content ?: "AI Generated Meal",
                changeSummary = parsedObj["changeSummary"]?.jsonPrimitive?.content
            )
        } catch (_: Throwable) {
            null
        }
    }
}
