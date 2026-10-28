package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.contract.AiChatMessage
import com.example.chatgpt_mealplanner.contract.AiContextPayload
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolId
import com.example.chatgpt_mealplanner.contract.AiToolResult
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.security.FakeCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class GeminiMealPlannerAiProviderTest {

    private lateinit var provider: GeminiMealPlannerAiProvider
    private lateinit var credentialStore: FakeCredentialStore

    private val emptyPayload = AiContextPayload(
        currentDate = "2026-09-28",
        dayOfWeek = "MONDAY",
        preferences = UserPreferencesSummaryDto(),
        recentCookedMeals = emptyList(),
        upcomingPlannedMeals = emptyList(),
        availableLeftovers = emptyList(),
        useSoonItems = emptyList(),
        lowStockStaples = emptyList(),
        activeShoppingList = emptyList()
    )

    private fun makeSuccessBody(text: String): String = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  { "text": "$text" }
                ]
              }
            }
          ]
        }
    """.trimIndent()

    private fun makeErrorBody(code: Int, message: String): String = """
        {
          "error": {
            "code": $code,
            "message": "$message"
          }
        }
    """.trimIndent()

    @Before
    fun setup() {
        credentialStore = FakeCredentialStore("test_api_key")
        provider = GeminiMealPlannerAiProvider(credentialStore = credentialStore, ioDispatcher = Dispatchers.Unconfined)
    }

    @Test
    fun modelChain_usesCorrectGemini37FlashId() {
        assertEquals(
            listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite"),
            GEMINI_MODEL_CHAIN
        )
    }

    @Test
    fun geminiProvider_blockingNetworkCall_doesNotExecuteOnMainDispatcher() = runBlocking {
        var executedOffMain = false
        // Test dispatcher override explicitly for off-main verification
        val ioProvider = GeminiMealPlannerAiProvider(credentialStore = credentialStore, ioDispatcher = Dispatchers.IO)
        ioProvider.httpCallHandler = { _, _ ->
            if (Thread.currentThread().name != "main") {
                executedOffMain = true
            }
            Pair(503, makeErrorBody(503, "Service Unavailable"))
        }

        val request = AiProviderRequest(
            conversationId = "test_conv",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = emptyList(),
            availableTools = emptyList()
        )

        val response = ioProvider.send(request)
        assertTrue(response is AiProviderResponse.Error)
        assertNotEquals("NO_API_KEY", (response as AiProviderResponse.Error).code)
        assertTrue("Network calls MUST execute off the main thread", executedOffMain)
    }

    @Test
    fun geminiProvider_existingCredential_reachesNetworkRequest() = runBlocking {
        credentialStore.saveKey("valid_persisted_key_456")
        var capturedUrl = ""
        provider.httpCallHandler = { url, _ ->
            capturedUrl = url
            Pair(200, makeSuccessBody("Success"))
        }

        val request = AiProviderRequest(
            conversationId = "test_conv",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = emptyList(),
            availableTools = emptyList()
        )

        val response = provider.send(request)
        assertTrue(capturedUrl.contains("key=valid_persisted_key_456"))
        assertNotEquals("NO_API_KEY", (response as? AiProviderResponse.Error)?.code)
    }

    @Test
    fun geminiProvider_networkFailure_returnsErrorNotSuccess() = runBlocking {
        provider.httpCallHandler = { _, _ ->
            Pair(503, makeErrorBody(503, "Network Unavailable"))
        }

        val request = AiProviderRequest(
            conversationId = "test_conv",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = emptyList(),
            availableTools = emptyList()
        )

        val response = provider.send(request)
        assertTrue("Network failures must return Error, never FinalResponse", response is AiProviderResponse.Error)
    }

    @Test
    fun geminiProvider_networkFailure_neverReturnsActionCompletedSuccessfully() = runBlocking {
        provider.httpCallHandler = { _, _ ->
            Pair(503, makeErrorBody(503, "Network Unavailable"))
        }

        val request = AiProviderRequest(
            conversationId = "test_conv",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = emptyList(),
            availableTools = emptyList()
        )

        val response = provider.send(request)
        val text = (response as? AiProviderResponse.FinalResponse)?.text ?: ""
        assertNotEquals("Action completed successfully.", text)
    }

    @Test
    fun primarySuccess_doesNotFallback() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            requestedModels.add(model)
            Pair(200, makeSuccessBody("Success from primary 3.7"))
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from primary 3.7", (res as AiProviderResponse.FinalResponse).text)
        assertEquals("gemini-3.7-flash", (res as AiProviderResponse.FinalResponse).modelId)
        assertEquals(listOf("gemini-3.7-flash"), requestedModels)
    }

    @Test
    fun primary429_immediatelyFallsBackTo36() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            requestedModels.add(model)
            if (model == "gemini-3.7-flash") {
                Pair(429, makeErrorBody(429, "Quota exceeded"))
            } else {
                Pair(200, makeSuccessBody("Success from 3.6-flash"))
            }
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from 3.6-flash", (res as AiProviderResponse.FinalResponse).text)
        assertEquals("gemini-3.6-flash", (res as AiProviderResponse.FinalResponse).modelId)
        assertEquals(listOf("gemini-3.7-flash", "gemini-3.6-flash"), requestedModels)
    }

    @Test
    fun primary503RetryThenFallsBackTo36() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            requestedModels.add(model)
            if (model == "gemini-3.7-flash") {
                Pair(503, makeErrorBody(503, "High demand"))
            } else {
                Pair(200, makeSuccessBody("Success from 3.6-flash"))
            }
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from 3.6-flash", (res as AiProviderResponse.FinalResponse).text)
        assertEquals("gemini-3.6-flash", (res as AiProviderResponse.FinalResponse).modelId)
        assertEquals(listOf("gemini-3.7-flash", "gemini-3.7-flash", "gemini-3.6-flash"), requestedModels)
    }

    @Test
    fun secondary429_immediatelyFallsBackTo35FlashLite() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            requestedModels.add(model)
            when (model) {
                "gemini-3.7-flash", "gemini-3.6-flash" -> Pair(429, makeErrorBody(429, "Quota limit"))
                "gemini-3.5-flash-lite" -> Pair(200, makeSuccessBody("Success from 3.5-flash-lite"))
                else -> Pair(400, makeErrorBody(400, "Unexpected model: $model"))
            }
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from 3.5-flash-lite", (res as AiProviderResponse.FinalResponse).text)
        assertEquals("gemini-3.5-flash-lite", (res as AiProviderResponse.FinalResponse).modelId)
        assertEquals(listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite"), requestedModels)
    }

    @Test
    fun secondary503RetryThenFallsBackTo35FlashLite() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            requestedModels.add(model)
            when (model) {
                "gemini-3.7-flash" -> Pair(429, makeErrorBody(429, "Quota"))
                "gemini-3.6-flash" -> Pair(503, makeErrorBody(503, "High demand"))
                else -> Pair(200, makeSuccessBody("Success from 3.5-flash-lite"))
            }
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from 3.5-flash-lite", (res as AiProviderResponse.FinalResponse).text)
        assertEquals(listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite"), requestedModels)
    }

    @Test
    fun modelSpecific404_immediatelySkipsModel() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            requestedModels.add(model)
            if (model == "gemini-3.7-flash") {
                Pair(404, makeErrorBody(404, "models/gemini-3.7-flash is not found for API version v1beta"))
            } else {
                Pair(200, makeSuccessBody("Success from fallback"))
            }
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from fallback", (res as AiProviderResponse.FinalResponse).text)
        assertEquals(listOf("gemini-3.7-flash", "gemini-3.6-flash"), requestedModels)
    }

    @Test
    fun flashLite_canCompleteRequest() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            requestedModels.add(model)
            if (model == "gemini-3.5-flash-lite") {
                Pair(200, makeSuccessBody("Complete from flash-lite"))
            } else {
                Pair(429, makeErrorBody(429, "Quota"))
            }
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Complete from flash-lite", (res as AiProviderResponse.FinalResponse).text)
        assertEquals(listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite"), requestedModels)
    }

    @Test
    fun globalAttemptLimit_neverExceeds4() = runBlocking {
        var totalHttpCalls = 0
        provider.httpCallHandler = { _, _ ->
            totalHttpCalls++
            Pair(503, makeErrorBody(503, "High demand across all models"))
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        provider.send(req)

        assertEquals("Hard global maximum attempts MUST NOT exceed 4", 4, totalHttpCalls)
    }

    @Test
    fun arbitrary404_doesNotBlindlyFallback() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            requestedModels.add(model)
            Pair(404, makeErrorBody(404, "Generic route not found"))
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.Error)
        assertEquals("HTTP_404", (res as AiProviderResponse.Error).code)
        assertEquals("Arbitrary 404 must NOT trigger fallback to secondary models", listOf("gemini-3.7-flash"), requestedModels)
    }

    @Test
    fun rawGoogleErrors_areNotShownToUser() = runBlocking {
        provider.httpCallHandler = { _, _ ->
            Pair(404, "{\"error\":{\"code\":404,\"message\":\"models/gemini-3.7-flash is not found for API version v1beta\"}}")
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.Error)
        val userMsg = (res as AiProviderResponse.Error).message
        assertFalse("User-facing error must NEVER contain raw JSON braces", userMsg.contains("{\"error\""))
        assertFalse("User-facing error must NEVER contain raw JSON key quotes", userMsg.contains("\"code\":404"))
    }

    @Test
    fun readToolFallback_doesNotClaimActionApplied() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            requestedModels.add(model)
            if (model == "gemini-3.7-flash") {
                Pair(503, makeErrorBody(503, "Busy"))
            } else {
                Pair(200, makeSuccessBody("Follow-up response after read tool"))
            }
        }

        val toolResults = listOf(
            AiToolResult(
                toolCallId = "call_read_123",
                toolName = "get_upcoming_planned_meals",
                success = true,
                payloadJson = "{\"meals\":[]}"
            )
        )
        val continueReq = AiContinueWithToolsRequest(
            conversationId = "conv1",
            providerContinuationToken = "conv1",
            toolResults = toolResults
        )

        val res = provider.continueWithToolResults(continueReq)
        assertTrue(res is AiProviderResponse.FinalResponse)
        val text = (res as AiProviderResponse.FinalResponse).text
        assertEquals("Follow-up response after read tool", text)
        assertFalse("READ continuation fallback must NOT claim action applied", text.contains("action was applied"))
    }

    @Test
    fun whatIsForDinner_neverClaimsActionAppliedWithoutCommittedWrite() = runBlocking {
        provider.httpCallHandler = { _, _ ->
            Pair(503, makeErrorBody(503, "All models busy"))
        }

        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "what is for dinner")), emptyList())
        val res = provider.send(req)

        assertTrue(res is AiProviderResponse.Error)
        val userMsg = (res as AiProviderResponse.Error).message
        assertFalse("what is for dinner must NEVER claim action applied", userMsg.contains("action was applied"))
        assertFalse("what is for dinner must NEVER claim applied successfully", userMsg.contains("applied successfully"))
    }

    @Test
    fun fallback_doesNotDuplicateUserMessage() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        provider.httpCallHandler = { urlStr, payload ->
            payloads.add(payload)
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            if (model == "gemini-3.7-flash") {
                Pair(429, makeErrorBody(429, "Quota exceeded"))
            } else {
                Pair(200, makeSuccessBody("Success"))
            }
        }

        val userMsg = AiChatMessage("user", "Unique user message 123")
        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(userMsg), emptyList())
        provider.send(req)

        assertEquals(2, payloads.size)
        val contents0 = payloads[0]["contents"]!!.jsonArray
        val contents1 = payloads[1]["contents"]!!.jsonArray
        assertEquals(1, contents0.size)
        assertEquals(1, contents1.size)
        assertEquals(contents0, contents1)
    }

    @Test
    fun fallback_doesNotDuplicateToolMutation() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        provider.httpCallHandler = { urlStr, payload ->
            payloads.add(payload)
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            if (model == "gemini-3.7-flash") {
                Pair(429, makeErrorBody(429, "Quota exceeded"))
            } else {
                Pair(200, makeSuccessBody("Success"))
            }
        }

        val tools = listOf(AiToolId.DELETE_INVENTORY_ITEM)
        val req = AiProviderRequest("conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "delete item")), tools)
        provider.send(req)

        assertEquals(2, payloads.size)
        assertEquals(payloads[0]["tools"], payloads[1]["tools"])
    }

    @Test
    fun fallbackAfterSuccessfulMutation_doesNotReplayMutation() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        provider.httpCallHandler = { urlStr, payload ->
            payloads.add(payload)
            val model = urlStr.substringAfter("/models/").substringBefore(":")
            if (model == "gemini-3.7-flash") {
                Pair(503, makeErrorBody(503, "Busy"))
            } else {
                Pair(200, makeSuccessBody("Mutation acknowledged"))
            }
        }

        val toolResults = listOf(
            AiToolResult(
                toolCallId = "call_123",
                toolName = "delete_inventory_item",
                success = true,
                payloadJson = "{\"status\":\"DELETED\"}"
            )
        )
        val continueReq = AiContinueWithToolsRequest(
            conversationId = "conv1",
            providerContinuationToken = "conv1",
            toolResults = toolResults
        )

        val res = provider.continueWithToolResults(continueReq)
        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Mutation acknowledged", (res as AiProviderResponse.FinalResponse).text)

        val fallbackPayload = payloads.last()
        val contents = fallbackPayload["contents"]!!.jsonArray
        val lastContent = contents.last().jsonObject
        assertTrue(lastContent.toString().contains("functionResponse"))
        assertTrue(lastContent.toString().contains("delete_inventory_item"))
    }

    @Test
    fun executeDiagnosticBisectionAndAudit() = runBlocking {
        val systemPrompt = "You are Pantry Planner AI, an expert household meal planning and inventory assistant.\nDatabase is authoritative. Use tools for dynamic queries. Household size default is 2."
        val contextJson = "{\"currentDate\":\"2026-09-28\",\"dayOfWeek\":\"MONDAY\",\"householdSize\":2,\"preferences\":{\"householdSize\":2,\"preferFrozenVegetables\":true,\"preferGarlicPowderOverFresh\":true},\"recentCookedMeals\":[],\"upcomingPlannedMeals\":[],\"availableLeftovers\":[],\"useSoonItems\":[],\"lowStockStaples\":[],\"activeShoppingList\":[]}"
        val userMsg = AiChatMessage(role = "user", content = "What can I make for dinner tonight?")

        val allTools = AiToolId.entries.toList()

        val buildToolMethod = GeminiMealPlannerAiProvider::class.java.getDeclaredMethod("buildToolDeclarationJson", AiToolId::class.java)
        buildToolMethod.isAccessible = true

        fun buildPayload(tools: List<AiToolId>): JsonObject {
            val contentsArray = buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", userMsg.content)
                        })
                    }
                })
            }
            val toolsArray = if (tools.isNotEmpty()) {
                buildJsonArray {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { tool ->
                                add(buildToolMethod.invoke(provider, tool) as JsonObject)
                            }
                        }
                    })
                }
            } else null

            return buildJsonObject {
                put("contents", contentsArray)
                put("systemInstruction", buildJsonObject {
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", "$systemPrompt\n\nContext:\n$contextJson")
                        })
                    }
                })
                if (toolsArray != null) {
                    put("tools", toolsArray)
                }
            }
        }

        val jsonPrinter = Json { prettyPrint = true }

        val counts = listOf(0, 1, 5, 10, 15, 20, 27)
        println("=== BISECTION TOOL PAYLOAD SIZES ===")
        counts.forEach { count ->
            val subset = allTools.take(count)
            val p = buildPayload(subset)
            val str = Json.encodeToString(p)
            val bytes = str.toByteArray(Charsets.UTF_8).size
            println("Tools Count: $count | Json Char Count: ${str.length} | UTF-8 Bytes: $bytes")
        }

        val fullPayload = buildPayload(allTools)
        val fullJsonStr = Json.encodeToString(fullPayload)
        val fullBytes = fullJsonStr.toByteArray(Charsets.UTF_8).size

        val sysText = "$systemPrompt\n\nContext:\n$contextJson"
        val sysObj = buildJsonObject {
            putJsonArray("parts") { add(buildJsonObject { put("text", sysText) }) }
        }
        val sysBytes = Json.encodeToString(sysObj).toByteArray(Charsets.UTF_8).size

        val msgObj = buildJsonObject {
            put("role", "user")
            putJsonArray("parts") { add(buildJsonObject { put("text", userMsg.content) }) }
        }
        val msgBytes = Json.encodeToString(msgObj).toByteArray(Charsets.UTF_8).size

        val toolSizes = allTools.map { tool ->
            val toolDecl = buildToolMethod.invoke(provider, tool) as JsonObject
            val toolStr = Json.encodeToString(toolDecl)
            tool to toolStr.toByteArray(Charsets.UTF_8).size
        }.sortedByDescending { it.second }

        val totalToolsBytes = toolSizes.sumOf { it.second }

        println("=== PAYLOAD BYTE BREAKDOWN (ALL 27 TOOLS) ===")
        println("Total Request Payload Bytes: $fullBytes")
        println("System Instruction Bytes: $sysBytes")
        println("User Message / History Bytes: $msgBytes")
        println("Tools Array Total Bytes: $totalToolsBytes")
        println("Top 5 Largest Tools:")
        toolSizes.take(5).forEachIndexed { idx, (tool, size) ->
            println("  ${idx + 1}. ${tool.toolName}: $size bytes")
        }

        val scratchDir = java.io.File("/home/proot/.cache/Google/AndroidStudio2026.1.3/projects/chatgpt_mealplanner.b861639a/.artifacts/1dbd63cc-043b-479f-b8a8-8cedec76bf9e/scratch")
        scratchDir.mkdirs()
        val dumpFile = java.io.File(scratchDir, "gemini_request_diagnostic_payload.json")
        dumpFile.writeText(jsonPrinter.encodeToString(fullPayload))
        println("Wrote diagnostic payload to ${dumpFile.absolutePath}")

        assertTrue(dumpFile.exists())
    }

    @Test
    fun provider_forcedTool_serializesGeminiToolConfig() = runBlocking {
        var capturedPayload: JsonObject? = null
        provider.httpCallHandler = { _, payload ->
            capturedPayload = payload
            Pair(200, makeSuccessBody("Success"))
        }

        val req = AiProviderRequest(
            conversationId = "conv1",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = listOf(AiChatMessage("user", "Hello")),
            availableTools = listOf(AiToolId.OFFER_ADD_PLANNED_MEAL),
            forcedToolName = "offer_add_planned_meal"
        )
        provider.send(req)

        assertTrue("Payload must contain toolConfig when forcedToolName is supplied", capturedPayload!!.containsKey("toolConfig"))
        assertFalse("Payload must use Gemini's camelCase API fields", capturedPayload!!.containsKey("tool_config"))
        val toolConfig = capturedPayload!!["toolConfig"]!!.jsonObject
        val fnCallingConfig = toolConfig["functionCallingConfig"]!!.jsonObject
        assertEquals("ANY", fnCallingConfig["mode"]!!.jsonPrimitive.content)
        assertFalse("Payload must use Gemini's camelCase API fields", fnCallingConfig.containsKey("allowed_function_names"))
        val allowedFns = fnCallingConfig["allowedFunctionNames"]!!.jsonArray
        assertEquals(1, allowedFns.size)
        assertEquals("offer_add_planned_meal", allowedFns[0].jsonPrimitive.content)
    }

    @Test
    fun normalProviderRequest_doesNotForceToolConfig() = runBlocking {
        var capturedPayload: JsonObject? = null
        provider.httpCallHandler = { _, payload ->
            capturedPayload = payload
            Pair(200, makeSuccessBody("Success"))
        }

        val req = AiProviderRequest(
            conversationId = "conv1",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = listOf(AiChatMessage("user", "Hello")),
            availableTools = listOf(
                AiToolId.OFFER_ADD_PLANNED_MEAL,
                AiToolId.GET_RETAIL_BASKET_COMPARISON,
                AiToolId.GET_RETAIL_ITEM_OPTIONS,
                AiToolId.SEARCH_RETAIL_PRODUCTS
            ),
            forcedToolName = null
        )
        provider.send(req)

        assertFalse("Normal provider request MUST NOT contain toolConfig", capturedPayload!!.containsKey("toolConfig"))
        val declarations = capturedPayload!!["tools"]!!.jsonArray
            .first().jsonObject["functionDeclarations"]!!.jsonArray
        val declaredNames = declarations.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("Active tools should still be declared", declaredNames.contains("offer_add_planned_meal"))
        assertFalse("Retired retailer comparison must not be exposed", declaredNames.contains("get_retail_basket_comparison"))
        assertFalse("Retired retailer options must not be exposed", declaredNames.contains("get_retail_item_options"))
        assertFalse("Retired retailer search must not be exposed", declaredNames.contains("search_retail_products"))
    }

    @Test
    fun globalTimeoutCap_constants_allowSlowGeminiResponsesUpTo120Seconds() {
        assertEquals(120_000L, com.example.chatgpt_mealplanner.core.ai.provider.GLOBAL_TIMEOUT_CAP_MS)
    }

    @Test
    fun send_whenTimeoutError_returnsTypedRequestTimeoutError() = runBlocking {
        provider.httpCallHandler = { _, _ ->
            throw java.net.SocketTimeoutException("Read timed out")
        }

        val req = AiProviderRequest(
            conversationId = "conv1",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = listOf(AiChatMessage("user", "Hello")),
            availableTools = emptyList()
        )

        val res = provider.send(req)
        assertTrue("Timeout MUST return AiProviderResponse.Error", res is AiProviderResponse.Error)
        val err = res as AiProviderResponse.Error
        assertEquals("CONNECT_TIMEOUT", err.code)
        assertTrue("Error message MUST mention timed out", err.message.contains("timed out"))
    }
}
