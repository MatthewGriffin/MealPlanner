package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.contract.RecipeStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val QUICK_REPLIES_MARKER = "QUICK_REPLIES:"
private const val PLAN_LEFT_MARKER = "PLAN_LEFT:"

/** Reads the meal plan or recipe the model writes into a text reply, and hides the JSON from the user. */
internal object ModelReplyText {

    private val json = Json { ignoreUnknownKeys = true }
    private val fencedJsonArray = Regex("""```(?:json)?\s*(\[[\s\S]*])\s*(?:```|$)""")

    /**
     * The model can end a reply with a QUICK_REPLIES: line holding a JSON array of short answers to the question it
     * asked. The line is for the app, so it comes out of the text; unreadable ones are dropped quietly.
     */
    fun withoutQuickReplies(text: String): Pair<String, List<String>> {
        val lines = text.lines()
        val index = lines.indexOfLast { it.trim().startsWith(QUICK_REPLIES_MARKER) }
        if (index < 0) return text to emptyList()
        val replies = try {
            json.parseToJsonElement(lines[index].trim().removePrefix(QUICK_REPLIES_MARKER).trim()).jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_QUICK_REPLY_CHARS }
                .distinct()
                .take(MAX_QUICK_REPLIES)
        } catch (_: Throwable) {
            emptyList()
        }
        val cleaned = lines.filterIndexed { i, _ -> i != index }.joinToString("\n").trim()
        return cleaned to replies
    }

    /**
     * While building a plan the model ends each reply with PLAN_LEFT: n, the meals still to propose after the current one.
     * The line is for the app, so it comes out of the text.
     */
    fun withoutPlanLeft(text: String): Pair<String, Int?> {
        val lines = text.lines()
        val index = lines.indexOfLast { it.trim().startsWith(PLAN_LEFT_MARKER) }
        if (index < 0) return text to null
        val left = lines[index].trim().removePrefix(PLAN_LEFT_MARKER).trim().toIntOrNull()?.coerceIn(0, 30)
        return lines.filterIndexed { i, _ -> i != index }.joinToString("\n").trim() to left
    }

    private const val MAX_QUICK_REPLIES = 4
    private const val MAX_QUICK_REPLY_CHARS = 40

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
