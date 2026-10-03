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
        assertEquals(29, AiToolId.entries.size)

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
}
