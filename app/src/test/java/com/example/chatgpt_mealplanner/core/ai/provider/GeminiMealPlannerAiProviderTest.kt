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
import kotlinx.serialization.json.putJsonObject
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

    private fun modelFrom(urlStr: String): String = urlStr.substringAfter("/models/").substringBefore(":")

    private fun helloRequest() = AiProviderRequest(
        "conv1", "Prompt", "{}", emptyPayload, listOf(AiChatMessage("user", "Hello")), emptyList()
    )

    @Test
    fun modelChain_usesSupportedGeminiModels() {
        assertEquals(
            listOf("gemini-3.6-flash", "gemini-3.5-flash-lite"),
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
        var capturedKey = ""
        provider.requestApiKeyObserver = { capturedKey = it }
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
        assertEquals("valid_persisted_key_456", capturedKey)
        assertFalse("API key must not be sent in the URL", capturedUrl.contains("valid_persisted_key_456"))
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
            requestedModels.add(modelFrom(urlStr))
            Pair(200, makeSuccessBody("Success from primary"))
        }

        val res = provider.send(helloRequest())

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from primary", (res as AiProviderResponse.FinalResponse).text)
        assertEquals(GEMINI_PRIMARY_MODEL, res.modelId)
        assertEquals(listOf(GEMINI_PRIMARY_MODEL), requestedModels)
    }

    @Test
    fun primary429_immediatelyFallsBackToSecondary() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = modelFrom(urlStr)
            requestedModels.add(model)
            if (model == GEMINI_PRIMARY_MODEL) {
                Pair(429, makeErrorBody(429, "Quota exceeded"))
            } else {
                Pair(200, makeSuccessBody("Success from secondary"))
            }
        }

        val res = provider.send(helloRequest())

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from secondary", (res as AiProviderResponse.FinalResponse).text)
        assertEquals(GEMINI_SECONDARY_MODEL, res.modelId)
        assertEquals(listOf(GEMINI_PRIMARY_MODEL, GEMINI_SECONDARY_MODEL), requestedModels)
    }

    @Test
    fun primary503RetryThenFallsBackToSecondary() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = modelFrom(urlStr)
            requestedModels.add(model)
            if (model == GEMINI_PRIMARY_MODEL) {
                Pair(503, makeErrorBody(503, "High demand"))
            } else {
                Pair(200, makeSuccessBody("Success from secondary"))
            }
        }

        val res = provider.send(helloRequest())

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from secondary", (res as AiProviderResponse.FinalResponse).text)
        assertEquals(GEMINI_SECONDARY_MODEL, res.modelId)
        assertEquals(listOf(GEMINI_PRIMARY_MODEL, GEMINI_PRIMARY_MODEL, GEMINI_SECONDARY_MODEL), requestedModels)
    }

    @Test
    fun modelSpecific404_immediatelySkipsModel() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = modelFrom(urlStr)
            requestedModels.add(model)
            if (model == GEMINI_PRIMARY_MODEL) {
                Pair(404, makeErrorBody(404, "models/$GEMINI_PRIMARY_MODEL is not found for API version v1beta"))
            } else {
                Pair(200, makeSuccessBody("Success from secondary"))
            }
        }

        val res = provider.send(helloRequest())

        assertTrue(res is AiProviderResponse.FinalResponse)
        assertEquals("Success from secondary", (res as AiProviderResponse.FinalResponse).text)
        assertEquals(GEMINI_SECONDARY_MODEL, res.modelId)
        assertEquals(listOf(GEMINI_PRIMARY_MODEL, GEMINI_SECONDARY_MODEL), requestedModels)
    }

    @Test
    fun allModels429_returnsErrorAfterTryingEachModelOnce() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = modelFrom(urlStr)
            requestedModels.add(model)
            if (model == GEMINI_PRIMARY_MODEL) {
                Pair(429, makeErrorBody(429, "Quota limit"))
            } else {
                Pair(429, makeErrorBody(429, "Quota limit"))
            }
        }

        val res = provider.send(helloRequest())

        assertTrue(res is AiProviderResponse.Error)
        assertEquals(listOf(GEMINI_PRIMARY_MODEL, GEMINI_SECONDARY_MODEL), requestedModels)
    }

    @Test
    fun secondary503_isRetriedBeforeReturningError() = runBlocking {
        val requestedModels = mutableListOf<String>()
        provider.httpCallHandler = { urlStr, _ ->
            val model = modelFrom(urlStr)
            requestedModels.add(model)
            if (model == GEMINI_PRIMARY_MODEL) {
                Pair(429, makeErrorBody(429, "Quota"))
            } else {
                Pair(503, makeErrorBody(503, "High demand"))
            }
        }

        val res = provider.send(helloRequest())

        assertTrue(res is AiProviderResponse.Error)
        assertEquals(listOf(GEMINI_PRIMARY_MODEL, GEMINI_SECONDARY_MODEL, GEMINI_SECONDARY_MODEL), requestedModels)
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
        assertEquals("Arbitrary 404 must NOT trigger fallback to secondary models", listOf(GEMINI_PRIMARY_MODEL), requestedModels)
    }

    @Test
    fun rawGoogleErrors_areNotShownToUser() = runBlocking {
        provider.httpCallHandler = { _, _ ->
            Pair(404, "{\"error\":{\"code\":404,\"message\":\"models/$GEMINI_PRIMARY_MODEL is not found for API version v1beta\"}}")
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
            if (model == GEMINI_PRIMARY_MODEL) {
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
            if (model == GEMINI_PRIMARY_MODEL) {
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
            if (model == GEMINI_PRIMARY_MODEL) {
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
            if (model == GEMINI_PRIMARY_MODEL) {
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

    private val toolCallBody = """
        {"candidates":[{"content":{"role":"model","parts":[
          {"functionCall":{"name":"get_usable_inventory","args":{}}}
        ]}}]}
    """.trimIndent()

    private fun recipeRequest(conversationId: String = "conv1", forcedTool: String? = null) = AiProviderRequest(
        conversationId = conversationId,
        systemPrompt = "RULES_$conversationId",
        contextPayloadJson = "{}",
        contextPayload = AiContextPayload(),
        messages = listOf(AiChatMessage("user", "dinner?")),
        availableTools = listOf(AiToolId.GET_USABLE_INVENTORY, AiToolId.OFFER_ADD_PLANNED_MEAL),
        forcedToolName = forcedTool
    )

    private fun inventoryResult(conversationId: String = "conv1", allowMoreToolCalls: Boolean = true) = AiContinueWithToolsRequest(
        conversationId = conversationId,
        providerContinuationToken = conversationId,
        toolResults = listOf(
            AiToolResult(toolCallId = "c1", toolName = "get_usable_inventory", success = true, payloadJson = "{}")
        ),
        allowMoreToolCalls = allowMoreToolCalls
    )

    @Test
    fun followUpAfterToolCall_keepsTheRulesContextAndTools() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        provider.httpCallHandler = { _, payload ->
            payloads.add(payload)
            Pair(200, if (payloads.size == 1) toolCallBody else makeSuccessBody("Done"))
        }

        provider.send(recipeRequest())
        provider.continueWithToolResults(inventoryResult())

        assertEquals(payloads[0]["systemInstruction"], payloads[1]["systemInstruction"])
        assertEquals(payloads[0]["tools"], payloads[1]["tools"])
        assertTrue(payloads[1]["tools"].toString().contains("offer_add_planned_meal"))
        assertFalse(payloads[1].containsKey("toolConfig"))
    }

    @Test
    fun followUpAfterForcedToolCall_doesNotForceTheToolAgain() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        provider.httpCallHandler = { _, payload ->
            payloads.add(payload)
            Pair(200, if (payloads.size == 1) toolCallBody else makeSuccessBody("Done"))
        }

        provider.send(recipeRequest(forcedTool = "offer_add_planned_meal"))
        provider.continueWithToolResults(inventoryResult())

        assertTrue(payloads[0].containsKey("toolConfig"))
        assertFalse(payloads[1].containsKey("toolConfig"))
    }

    @Test
    fun lastAllowedRound_tellsTheModelToAnswerInText() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        provider.httpCallHandler = { _, payload ->
            payloads.add(payload)
            Pair(200, if (payloads.size == 1) toolCallBody else makeSuccessBody("Done"))
        }

        provider.send(recipeRequest())
        provider.continueWithToolResults(inventoryResult(allowMoreToolCalls = false))

        assertEquals("NONE", payloads[1]["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertTrue(payloads[1].containsKey("tools"))
    }

    @Test
    fun toolCallsMadeTogether_areAnsweredInOneMessage() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        provider.httpCallHandler = { _, payload ->
            payloads.add(payload)
            Pair(200, if (payloads.size == 1) toolCallBody else makeSuccessBody("Done"))
        }

        provider.send(recipeRequest())
        provider.continueWithToolResults(
            inventoryResult().copy(
                toolResults = listOf(
                    AiToolResult(toolCallId = "c1", toolName = "get_usable_inventory", success = true, payloadJson = "{}"),
                    AiToolResult(toolCallId = "c2", toolName = "get_use_soon_items", success = true, payloadJson = "{}")
                )
            )
        )

        val contents = payloads[1]["contents"]!!.jsonArray
        val answer = contents.last().jsonObject
        assertEquals("model", contents[contents.size - 2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", answer["role"]!!.jsonPrimitive.content)
        assertEquals(2, answer["parts"]!!.jsonArray.size)
    }

    private fun candidateBody(finishReason: String, text: String) = buildJsonObject {
        putJsonArray("candidates") {
            add(buildJsonObject {
                put("finishReason", finishReason)
                putJsonObject("content") {
                    put("role", "model")
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                }
            })
        }
    }.toString()

    private fun errorCodeFor(body: String): String? {
        provider.httpCallHandler = { _, _ -> Pair(200, body) }
        return (runBlocking { provider.send(recipeRequest()) } as? AiProviderResponse.Error)?.code
    }

    @Test
    fun cutOffReply_isRefusedInsteadOfShownAsFinished() {
        assertEquals("RESPONSE_TRUNCATED", errorCodeFor(candidateBody("MAX_TOKENS", "Here is half a rec")))
    }

    @Test
    fun blockedReplies_sayWhatHappened() {
        assertEquals("RESPONSE_BLOCKED", errorCodeFor(candidateBody("SAFETY", "text")))
        assertEquals("RESPONSE_BLOCKED", errorCodeFor(candidateBody("SOME_NEW_REASON", "text")))
        assertEquals("RESPONSE_BLOCKED", errorCodeFor("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
    }

    @Test
    fun malformedToolCall_isRetriedThenFallsBack() = runBlocking {
        val models = mutableListOf<String>()
        provider.httpCallHandler = { url, _ ->
            models.add(url.substringAfter("/models/").substringBefore(":"))
            Pair(200, if (models.size < 3) candidateBody("MALFORMED_FUNCTION_CALL", "") else makeSuccessBody("Fine"))
        }

        val response = provider.send(recipeRequest())

        assertEquals(GEMINI_SECONDARY_MODEL, models.last())
        assertTrue(response is AiProviderResponse.FinalResponse)
    }

    @Test
    fun normallyFinishedReply_isAccepted() {
        assertEquals(null, errorCodeFor(candidateBody("STOP", "Dinner idea")))
    }

    private fun finalReplyFor(text: String): AiProviderResponse {
        provider.httpCallHandler = { _, _ -> Pair(200, candidateBody("STOP", text)) }
        return runBlocking { provider.send(recipeRequest()) }
    }

    private val validPlanJson = """[
        {"title":"Pasta","suggestedDate":"2026-11-06","mealType":"DINNER","servings":2,"ingredients":[{"displayName":"Penne","quantity":200,"unit":"GRAM"}],"steps":[{"order":1,"instruction":"Boil"}]},
        {"title":"Curry","suggestedDate":"2026-11-07","mealType":"DINNER","servings":2,"ingredients":[{"displayName":"Rice","quantity":200,"unit":"GRAM"}],"steps":[{"order":1,"instruction":"Simmer"}]}
    ]"""

    @Test
    fun unreadableMealPlanJson_isNeverShownToTheUser() {
        val response = finalReplyFor("Your week:\nMonday: Pasta\nMEAL_PLAN_JSON: [{\"title\":\"Pasta")

        val final = response as AiProviderResponse.FinalResponse
        assertEquals("Your week:\nMonday: Pasta", final.text)
        assertEquals(null, final.mealPlan)
    }

    @Test
    fun replyThatIsOnlyUnreadableMealPlanJson_isAnError() {
        val response = finalReplyFor("MEAL_PLAN_JSON: [{\"title\":\"Pasta")

        assertEquals("RESPONSE_INVALID", (response as AiProviderResponse.Error).code)
    }

    @Test
    fun mealPlanJsonInACodeFence_isStillRead() {
        val response = finalReplyFor("Your week\nMEAL_PLAN_JSON:\n```json\n$validPlanJson\n```")

        val final = response as AiProviderResponse.FinalResponse
        assertEquals(listOf("Pasta", "Curry"), final.mealPlan?.map { it.title })
        assertEquals("Your week", final.text)
    }

    @Test
    fun boldWordsInAReply_areNotARecipe() {
        val response = finalReplyFor("You have **milk** and **eggs** in the fridge.")

        assertEquals(null, (response as AiProviderResponse.FinalResponse).proposal)
    }

    @Test
    fun recipeCardTool_asksForStepsTimesAndKitchenMeasures() = runBlocking {
        var payload: JsonObject? = null
        provider.httpCallHandler = { _, p -> payload = p; Pair(200, makeSuccessBody("Done")) }

        provider.send(recipeRequest())

        val declarations = payload!!["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        val offer = declarations.first { it.jsonObject["name"]!!.jsonPrimitive.content == "offer_add_planned_meal" }.jsonObject["parameters"]!!.jsonObject
        val properties = offer["properties"]!!.jsonObject
        listOf("steps", "prepMinutes", "cookMinutes", "equipment", "whyThisSuits").forEach { assertTrue(it, properties.containsKey(it)) }
        assertFalse(properties.containsKey("instructions"))
        assertTrue(offer["required"].toString().contains("steps"))
        val ingredient = properties["ingredients"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        listOf("measure", "optional", "substitute").forEach { assertTrue(it, ingredient.containsKey(it)) }
        assertFalse(ingredient["unit"].toString().contains("ITEM"))
    }

    @Test
    fun mealPlanJson_keepsTimesMeasuresAndStepLengths() {
        val plan = """[
            {"title":"Pasta","suggestedDate":"2026-11-06","mealType":"DINNER","servings":2,"prepMinutes":5,"cookMinutes":15,"whyThisSuits":"Quick.","ingredients":[{"displayName":"Oregano","quantity":1,"unit":"GRAM","measure":"1 tsp","substitute":"basil"}],"steps":[{"order":1,"instruction":"Boil","durationMinutes":10}]},
            {"title":"Curry","suggestedDate":"2026-11-07","mealType":"DINNER","servings":2,"ingredients":[{"displayName":"Rice","quantity":200,"unit":"GRAM"}],"steps":[{"order":1,"instruction":"Simmer"}]}
        ]"""

        val first = (finalReplyFor("Your week\nMEAL_PLAN_JSON: $plan") as AiProviderResponse.FinalResponse).mealPlan!!.first()

        assertEquals(20, first.totalMinutes())
        assertEquals("Quick.", first.reasoningSummary)
        assertEquals("1 tsp", first.ingredients.single().measure)
        assertEquals("basil", first.ingredients.single().substitute)
        assertEquals(10, first.steps.single().durationMinutes)
    }

        @Test
    fun twoConversations_doNotShareToolCallState() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        provider.httpCallHandler = { _, payload ->
            payloads.add(payload)
            Pair(200, if (payloads.size <= 2) toolCallBody else makeSuccessBody("Done"))
        }

        provider.send(recipeRequest("first"))
        provider.send(recipeRequest("second"))
        provider.continueWithToolResults(inventoryResult("first"))

        assertTrue(payloads[2]["systemInstruction"].toString().contains("RULES_first"))
        assertFalse(payloads[2]["systemInstruction"].toString().contains("RULES_second"))
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
    fun requestWithOnlyRetiredRetailTools_omitsToolsFromGeminiPayload() = runBlocking {
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
            messages = listOf(AiChatMessage("user", "Compare retailer prices")),
            availableTools = listOf(
                AiToolId.GET_RETAIL_BASKET_COMPARISON,
                AiToolId.GET_RETAIL_ITEM_OPTIONS,
                AiToolId.SEARCH_RETAIL_PRODUCTS
            ),
            forcedToolName = AiToolId.SEARCH_RETAIL_PRODUCTS.toolName
        )
        provider.send(req)

        assertFalse(
            "Do not send an empty functionDeclarations list when all requested tools are retired",
            capturedPayload!!.containsKey("tools")
        )
        assertFalse(
            "Do not force a retired tool that is absent from Gemini declarations",
            capturedPayload!!.containsKey("toolConfig")
        )
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
