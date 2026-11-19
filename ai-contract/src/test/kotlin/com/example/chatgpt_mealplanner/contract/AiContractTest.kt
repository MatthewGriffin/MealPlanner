package com.example.chatgpt_mealplanner.contract

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun verifyAiContractVersion_andUniqueTools() {
        assertEquals("ai-contract-v2", AI_CONTRACT_VERSION)
        assertEquals(35, AiToolId.entries.size)

        val names = AiToolId.entries.map { it.toolName }
        assertEquals(names.size, names.toSet().size, "All tool names must be unique.")
    }

    @Test
    fun toolCallAndResultSerialization_roundTripsCorrectly() {
        val call = AiToolCall("c1", "get_inventory_summary", "{}")
        val serializedCall = json.encodeToString(AiToolCall.serializer(), call)
        val deserializedCall = json.decodeFromString<AiToolCall>(serializedCall)
        assertEquals(call, deserializedCall)

        val result = AiToolResult("c1", "get_inventory_summary", true, "{\"status\":\"OK\"}")
        val serializedResult = json.encodeToString(AiToolResult.serializer(), result)
        val deserializedResult = json.decodeFromString<AiToolResult>(serializedResult)
        assertEquals(result, deserializedResult)
    }

    @Test
    fun aWebRecipesSource_isKeptWithTheSavedMethod_andOlderSavedCardsStillRead() {
        val proposal = MealProposal(
            title = "Shawarma traybake",
            ingredients = emptyList(),
            steps = listOf(RecipeStep(order = 1, instruction = "Roast")),
            reasoningSummary = "",
            source = "The Happy Foodie: https://thehappyfoodie.co.uk/recipes/shawarma-chicken-traybake/"
        )

        assertTrue(proposal.savedInstructions().endsWith("Based on: The Happy Foodie: https://thehappyfoodie.co.uk/recipes/shawarma-chicken-traybake/"))
        val older = Json { ignoreUnknownKeys = true }.decodeFromString(MealProposal.serializer(), """{"title":"Old","ingredients":[],"reasoningSummary":""}""")
        assertEquals(null, older.source)
    }

    @Test
    fun extrasWorthAdding_areKeptWithTheSavedMethod() {
        val proposal = MealProposal(
            title = "Pasta", ingredients = emptyList(), steps = listOf(RecipeStep(order = 1, instruction = "Boil")),
            reasoningSummary = "", worthAdding = listOf("Fresh basil: adds freshness")
        )

        assertTrue(proposal.savedInstructions().contains("Worth adding: Fresh basil: adds freshness"))
    }
}
