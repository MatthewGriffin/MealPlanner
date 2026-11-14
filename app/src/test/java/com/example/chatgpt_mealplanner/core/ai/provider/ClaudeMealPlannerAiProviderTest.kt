package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.contract.AiChatMessage
import com.example.chatgpt_mealplanner.contract.AiContextPayload
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolId
import com.example.chatgpt_mealplanner.contract.AiToolResult
import com.example.chatgpt_mealplanner.core.security.FakeCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

class ClaudeMealPlannerAiProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: ClaudeMealPlannerAiProvider

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        provider = ClaudeMealPlannerAiProvider(FakeCredentialStore("sk-ant-test"), Dispatchers.Unconfined).apply {
            baseUrl = server.url("/").toString().removeSuffix("/")
            maxRetries = 0
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun reply(stopReason: String, vararg content: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
            "content":[${content.joinToString(",")}],"stop_reason":"$stopReason","stop_sequence":null,
            "usage":{"input_tokens":10,"output_tokens":5}}"""
        )

    private fun text(value: String) = """{"type":"text","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(value))}}"""
    private fun toolUse(id: String, name: String, input: String = "{}") = """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""
    private fun textReply(value: String) = reply("end_turn", text(value))

    private fun error(status: Int, type: String, message: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"type":"error","error":{"type":"$type","message":"$message"}}""")

    /** The SDK sends the key as a bearer token. Older versions used x-api-key. */
    private fun keyIn(request: okhttp3.mockwebserver.RecordedRequest): String? =
        request.getHeader("x-api-key") ?: request.getHeader("Authorization")?.removePrefix("Bearer ")

    private fun nextBody(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun request(
        conversationId: String = "conv1",
        messages: List<AiChatMessage> = listOf(AiChatMessage("user", "dinner?")),
        tools: List<AiToolId> = listOf(AiToolId.GET_USABLE_INVENTORY, AiToolId.OFFER_ADD_PLANNED_MEAL),
        forcedTool: String? = null
    ) = AiProviderRequest(
        conversationId = conversationId,
        systemPrompt = "RULES_$conversationId",
        contextPayloadJson = """{"pantry":"eggs"}""",
        contextPayload = AiContextPayload(),
        messages = messages,
        availableTools = tools,
        forcedToolName = forcedTool
    )

    private fun results(conversationId: String = "conv1", allowMore: Boolean = true, vararg idsAndNames: Pair<String, String>) =
        AiContinueWithToolsRequest(
            conversationId = conversationId,
            providerContinuationToken = conversationId,
            toolResults = idsAndNames.map { (id, name) -> AiToolResult(toolCallId = id, toolName = name, success = true, payloadJson = """{"items":["eggs"]}""") },
            allowMoreToolCalls = allowMore
        )

    @Test
    fun aTextReply_isReturnedWithTheModel_andTheRequestCarriesKeyRulesAndContext() = runBlocking {
        server.enqueue(textReply("Try an omelette."))

        val response = provider.send(request()) as AiProviderResponse.FinalResponse

        assertEquals("Try an omelette.", response.text)
        assertEquals(CLAUDE_MODEL_ID, response.modelId)
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-ant-test", keyIn(recorded))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(CLAUDE_MODEL_ID, body["model"]!!.jsonPrimitive.content)
        val system = body["system"].toString()
        assertTrue(system.contains("RULES_conv1"))
        assertTrue(system.contains("pantry"))
        assertEquals("user", body["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun theDefaultModel_isHaiku_andTheEvaluationCanChooseSonnet() = runBlocking {
        assertEquals("claude-haiku-5-5", CLAUDE_MODEL_ID)
        provider.model = CLAUDE_SONNET_MODEL_ID
        server.enqueue(textReply("Hi"))

        provider.send(request())

        assertEquals("claude-sonnet-5-5", nextBody()["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun noSavedKey_isAnErrorWithoutAnyRequest() = runBlocking {
        val noKey = ClaudeMealPlannerAiProvider(FakeCredentialStore(null), Dispatchers.Unconfined).apply {
            baseUrl = server.url("/").toString().removeSuffix("/")
        }

        val response = noKey.send(request()) as AiProviderResponse.Error

        assertEquals("NO_API_KEY", response.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun toolCalls_keepClaudesIdsAndArguments() = runBlocking {
        server.enqueue(reply("tool_use", text("Checking."), toolUse("toolu_A", "get_inventory_for_ingredient", """{"searchQuery":"milk"}""")))

        val response = provider.send(request(tools = listOf(AiToolId.GET_INVENTORY_FOR_INGREDIENT))) as AiProviderResponse.ToolCalls

        val call = response.calls.single()
        assertEquals("toolu_A", call.toolCallId)
        assertEquals("get_inventory_for_ingredient", call.toolName)
        assertEquals("milk", Json.parseToJsonElement(call.argsJson).jsonObject["searchQuery"]!!.jsonPrimitive.content)
    }

    @Test
    fun followUpAfterToolCall_resendsRulesToolsAndClaudesTurn_thenTheResult() = runBlocking {
        server.enqueue(reply("tool_use", toolUse("toolu_A", "get_usable_inventory")))
        server.enqueue(textReply("Omelette it is."))

        provider.send(request())
        val final = provider.continueWithToolResults(results(idsAndNames = arrayOf("toolu_A" to "get_usable_inventory")))

        assertEquals("Omelette it is.", (final as AiProviderResponse.FinalResponse).text)
        val first = nextBody()
        val second = nextBody()
        assertEquals(first["system"], second["system"])
        assertEquals(first["tools"], second["tools"])
        val messages = second["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertTrue(messages[1].toString().contains("toolu_A"))
        val result = messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_A", result["tool_use_id"]!!.jsonPrimitive.content)
        assertTrue(result["content"].toString().contains("eggs"))
    }

    @Test
    fun toolCallsMadeTogether_areAnsweredInOneMessage_andAMissingResultIsReportedAsAnError() = runBlocking {
        server.enqueue(reply("tool_use", toolUse("toolu_A", "get_usable_inventory"), toolUse("toolu_B", "get_use_soon_items")))
        server.enqueue(textReply("Done"))

        provider.send(request(tools = listOf(AiToolId.GET_USABLE_INVENTORY, AiToolId.GET_USE_SOON_ITEMS)))
        provider.continueWithToolResults(results(idsAndNames = arrayOf("toolu_A" to "get_usable_inventory")))

        nextBody()
        val answer = nextBody()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("toolu_A", "toolu_B"), answer.map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content })
        assertEquals("true", answer[1].jsonObject["is_error"]!!.jsonPrimitive.content)
    }

    @Test
    fun lastAllowedRound_tellsClaudeNotToCallMoreTools() = runBlocking {
        server.enqueue(reply("tool_use", toolUse("toolu_A", "get_usable_inventory")))
        server.enqueue(textReply("Done"))

        provider.send(request())
        provider.continueWithToolResults(results(allowMore = false, idsAndNames = arrayOf("toolu_A" to "get_usable_inventory")))

        assertEquals("auto", nextBody()["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("none", nextBody()["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun aForcedTool_isAskedForInTheRules_becauseForcingIsRejectedByCurrentModels() = runBlocking {
        server.enqueue(textReply("ok"))

        provider.send(request(forcedTool = "offer_add_planned_meal"))

        val body = nextBody()
        assertEquals("auto", body["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(body["system"].toString().contains("calling the 'offer_add_planned_meal' tool"))
    }

    @Test
    fun aNormalRequest_doesNotAskForAnyTool() = runBlocking {
        server.enqueue(textReply("ok"))

        provider.send(request())

        assertFalse(nextBody()["system"].toString().contains("calling the"))
    }

    @Test
    fun retiredRetailTools_areNeverOffered_andNoToolsMeansNoToolChoice() = runBlocking {
        server.enqueue(textReply("ok"))

        provider.send(request(tools = listOf(AiToolId.SEARCH_RETAIL_PRODUCTS, AiToolId.GET_RETAIL_ITEM_OPTIONS)))

        val body = nextBody()
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("tool_choice"))
    }

    @Test
    fun recipeCardTool_asksForStepsTimesAndKitchenMeasures() = runBlocking {
        server.enqueue(textReply("ok"))

        provider.send(request())

        val offer = nextBody()["tools"]!!.jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "offer_add_planned_meal" }
            .jsonObject["input_schema"]!!.jsonObject
        assertEquals("object", offer["type"]!!.jsonPrimitive.content)
        val properties = offer["properties"]!!.jsonObject
        listOf("steps", "prepMinutes", "cookMinutes", "equipment", "whyThisSuits").forEach { assertTrue(it, properties.containsKey(it)) }
        assertFalse(properties.containsKey("instructions"))
        assertTrue(offer["required"].toString().contains("steps"))
        val ingredient = properties["ingredients"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        listOf("measure", "optional", "substitute").forEach { assertTrue(it, ingredient.containsKey(it)) }
        assertEquals("number", ingredient["quantity"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun tools_listTheUnitsTheAppCanRead_andAreNotStrict() = runBlocking {
        server.enqueue(textReply("ok"))

        provider.send(request())

        val body = nextBody()
        val offer = body["tools"]!!.jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "offer_add_planned_meal" }.jsonObject
        // Strict tools made Haiku send empty or placeholder steps in a live run.
        assertFalse(offer["strict"]?.jsonPrimitive?.content == "true")
        val schema = offer["input_schema"]!!.jsonObject
        val ingredient = schema["properties"]!!.jsonObject["ingredients"]!!.jsonObject["items"]!!.jsonObject
        val units = ingredient["properties"]!!.jsonObject["unit"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.entries.map { it.name }, units)
        assertEquals("1", schema["properties"]!!.jsonObject["steps"]!!.jsonObject["minItems"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun webSearch_isOfferedOnlyWhenAllowed_withAFewSearchesAtMost() = runBlocking {
        server.enqueue(textReply("ok"))
        server.enqueue(textReply("ok"))

        provider.send(request())
        provider.send(request().copy(allowWebSearch = true))

        fun webTool(body: JsonObject) = body["tools"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == "web_search" }
        assertNull(webTool(nextBody()))
        val tool = webTool(nextBody())!!
        assertEquals("web_search_20250305", tool["type"]!!.jsonPrimitive.content)
        assertEquals("2", tool["max_uses"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPausedSearch_isSentBackSoClaudeCanFinish_andSearchesAreCounted() = runBlocking {
        val usage = mutableListOf<TokenUsage>()
        provider.usageObserver = { usage += it }
        val search = """{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"shawarma traybake"}}"""
        val results = """{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://example.com/shawarma","title":"Shawarma","encrypted_content":"abc","page_age":null}]}"""
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[$search,$results],
                "stop_reason":"pause_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5,"server_tool_use":{"web_search_requests":1}}}"""
            )
        )
        server.enqueue(textReply("Here is a shawarma traybake idea."))

        val response = provider.send(request().copy(allowWebSearch = true)) as AiProviderResponse.FinalResponse

        assertEquals("Here is a shawarma traybake idea.", response.text)
        nextBody()
        val second = nextBody()["messages"]!!.jsonArray
        assertEquals("assistant", second.last().jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(second.last().toString().contains("srvtoolu_1"))
        assertEquals(1L, usage.sumOf { it.webSearches })
    }

    @Test
    fun theUnchangingRulesAndTools_endCachedPrefixes_andTheContextComesAfter() = runBlocking {
        server.enqueue(textReply("ok"))

        provider.send(request().copy(allowWebSearch = true))

        val body = nextBody()
        val system = body["system"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, system.size)
        assertEquals("RULES_conv1", system[0]["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system[0]["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(system[1]["text"]!!.jsonPrimitive.content.contains("pantry"))
        assertNull(system[1]["cache_control"])
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        // The marker goes on the app's last tool, so adding web search doesn't change the cached prefix.
        assertEquals("offer_add_planned_meal", tools.last { it.containsKey("cache_control") }["name"]!!.jsonPrimitive.content)
        assertEquals(1, tools.count { it.containsKey("cache_control") })
        assertEquals("web_search", tools.last()["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun twoConversations_doNotShareToolCallState() = runBlocking {
        server.enqueue(reply("tool_use", toolUse("toolu_first", "get_usable_inventory")))
        server.enqueue(reply("tool_use", toolUse("toolu_second", "get_usable_inventory")))
        server.enqueue(textReply("Done"))

        provider.send(request("first"))
        provider.send(request("second"))
        provider.continueWithToolResults(results("first", idsAndNames = arrayOf("toolu_first" to "get_usable_inventory")))

        nextBody(); nextBody()
        val third = nextBody()
        assertTrue(third["system"].toString().contains("RULES_first"))
        assertFalse(third.toString().contains("toolu_second"))
    }

    @Test
    fun continuingAConversationThatWasNeverStarted_isAnError() = runBlocking {
        val response = provider.continueWithToolResults(results(idsAndNames = arrayOf("toolu_X" to "get_usable_inventory")))

        assertEquals("CONTINUATION_MISSING", (response as AiProviderResponse.Error).code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cutOffAndRefusedReplies_areErrors() = runBlocking {
        server.enqueue(reply("max_tokens", text("Here is half a rec")))
        server.enqueue(reply("refusal", text("")))

        assertEquals("RESPONSE_TRUNCATED", (provider.send(request()) as AiProviderResponse.Error).code)
        assertEquals("RESPONSE_BLOCKED", (provider.send(request()) as AiProviderResponse.Error).code)
    }

    @Test
    fun aCutOffReply_isStillCountedInTheUsage() = runBlocking {
        val usage = mutableListOf<TokenUsage>()
        provider.usageObserver = { usage += it }
        server.enqueue(reply("max_tokens", text("Half a plan")))

        provider.send(request())

        assertEquals(5L, usage.single().output)
    }

    @Test
    fun aRejectedKey_pointsToSettings() = runBlocking {
        server.enqueue(error(401, "authentication_error", "invalid x-api-key"))

        val response = provider.send(request()) as AiProviderResponse.Error

        assertEquals("HTTP_401", response.code)
        assertTrue(response.message.contains("Settings"))
    }

    @Test
    fun busyAndRateLimited_areTypedErrors_withoutRawJson() = runBlocking {
        server.enqueue(error(529, "overloaded_error", "Overloaded"))
        server.enqueue(error(429, "rate_limit_error", "Number of requests has exceeded your rate limit"))

        val busy = provider.send(request()) as AiProviderResponse.Error
        val limited = provider.send(request()) as AiProviderResponse.Error

        assertEquals("HTTP_529", busy.code)
        assertFalse(busy.message.contains("{"))
        assertEquals("HTTP_429", limited.code)
        assertFalse(limited.message.contains("applied"))
    }

    @Test
    fun aBusyServer_isRetriedByTheSdk() = runBlocking {
        provider.maxRetries = 1
        server.enqueue(error(529, "overloaded_error", "Overloaded").setHeader("retry-after-ms", "10"))
        server.enqueue(textReply("Second time lucky"))

        val response = provider.send(request())

        assertEquals("Second time lucky", (response as AiProviderResponse.FinalResponse).text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun noAnswerInTime_isAReadTimeout() = runBlocking {
        provider.requestTimeout = Duration.ofMillis(500)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val response = provider.send(request()) as AiProviderResponse.Error

        assertEquals("READ_TIMEOUT", response.code)
    }

    @Test
    fun theConnectionTest_reportsTheModel() = runBlocking {
        server.enqueue(textReply("OK"))

        val response = provider.testConnection() as AiProviderResponse.FinalResponse

        assertTrue(response.text.contains(CLAUDE_MODEL_ID))
    }

    @Test
    fun messages_dropBlanks_mergeRepeatedTurns_andStartAndEndWithTheUser() {
        val params = provider.toMessageParams(
            listOf(
                AiChatMessage("assistant", "Welcome back"),
                AiChatMessage("user", "dinner?"),
                AiChatMessage("assistant", ""),
                AiChatMessage("user", "with eggs"),
                AiChatMessage("assistant", "Omelette")
            )
        )

        assertEquals(listOf(true, false, true, false, true), params.map { it.isUser() })
        assertTrue(params[2].asUser().content().toString().contains("dinner?"))
        assertTrue(params[2].asUser().content().toString().contains("with eggs"))
    }

    @Test
    fun theGlobalCap_coversARequestAndItsRetries() {
        assertEquals(180_000L, GLOBAL_TIMEOUT_CAP_MS)
    }

    private fun finalReplyFor(value: String): AiProviderResponse {
        server.enqueue(textReply(value))
        return runBlocking { provider.send(request()) }
    }

    @Test
    fun otherCodeFencesAndBoldWords_areLeftAlone() {
        val reply = "Try this:\n```json\n[1, 2]\n```"
        val fenced = finalReplyFor(reply) as AiProviderResponse.FinalResponse
        assertEquals(reply, fenced.text)

        assertNull((finalReplyFor("You have **milk** and **eggs**.") as AiProviderResponse.FinalResponse).proposal)
    }

    @Test
    fun parsePantryList_sendsTheListAndReadsTheToolCall() = runBlocking {
        server.enqueue(
            reply(
                "tool_use",
                toolUse(
                    "t1", PantryListParsing.TOOL_NAME,
                    """{"items":[{"displayName":"Chopped tomatoes","canonicalName":"chopped tomatoes","category":"TINS_CANS","storageLocation":"CUPBOARD","quantity":3,"unit":"TIN","quantityGuessed":false}]}"""
                )
            )
        )
        val result = provider.parsePantryList("3 tins chopped tomatoes")
        val item = (result as PantryListResult.Items).items.single()
        assertEquals("Chopped tomatoes", item.displayName)
        assertEquals(3.0, item.quantity, 0.0)
        val body = nextBody()
        assertEquals("3 tins chopped tomatoes", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(PantryListParsing.TOOL_NAME, body["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsePantryList_failsClearlyWhenNothingWasFoundOrBlank() = runBlocking {
        server.enqueue(textReply("There is no food in that."))
        assertTrue(provider.parsePantryList("hello") is PantryListResult.Failed)
        assertTrue(provider.parsePantryList("   ") is PantryListResult.Failed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun parsePantryList_reportsApiErrors() = runBlocking {
        server.enqueue(error(401, "authentication_error", "bad key"))
        val result = provider.parsePantryList("rice")
        assertTrue((result as PantryListResult.Failed).message.contains("API key"))
    }

    @Test
    fun chooseProducts_sendsTheCandidatesAndReadsTheChoices() = runBlocking {
        server.enqueue(reply("tool_use", toolUse("t1", PantryListParsing.MATCH_TOOL_NAME, """{"choices":[{"line":3,"candidate":1,"sure":true}]}""")))
        val result = provider.chooseProducts(
            listOf(ProductMatchLine(3, "tilda basmati", listOf(ProductCandidate("Basmati", "Tilda", "1000 gram"), ProductCandidate("Basmati Rice", "Tilda", "500 gram"))))
        )
        assertEquals(listOf(ProductMatchChoice(3, 1, true)), (result as ProductMatchResult.Choices).choices)
        val body = nextBody()
        val sent = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(sent.contains("Line 3") && sent.contains("1: Basmati Rice | brand: Tilda | size: 500 gram"))
        assertEquals(PantryListParsing.MATCH_TOOL_NAME, body["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun chooseProducts_makesNoRequestForNoLines() = runBlocking {
        assertEquals(emptyList<ProductMatchChoice>(), (provider.chooseProducts(emptyList()) as ProductMatchResult.Choices).choices)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aPhotoOnTheUsersMessageIsSentToClaudeAsAnImageBeforeTheWords() = runBlocking {
        server.enqueue(textReply("Thanks, that packet is clear."))
        provider.send(request(messages = listOf(AiChatMessage("user", "here is the packet", imagesJpegBase64 = listOf("QUJD")))))

        val content = nextBody()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        val image = content[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("base64", image["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", image["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(content[1].jsonObject["text"]!!.jsonPrimitive.content.contains("here is the packet"))
    }

    @Test
    fun messagesWithoutAPhotoStayPlainText() = runBlocking {
        server.enqueue(textReply("ok"))
        provider.send(request(messages = listOf(AiChatMessage("user", "hello"))))

        val last = nextBody()["messages"]!!.jsonArray.last().jsonObject["content"]!!
        assertEquals("hello", last.jsonPrimitive.content)
    }

    @Test
    fun aPhotoSurvivesTwoUserMessagesBeingMergedIntoOneTurn() {
        val turns = provider.toMessageParams(
            listOf(AiChatMessage("user", "first"), AiChatMessage("user", "the packet", imagesJpegBase64 = listOf("QUJD")))
        )
        assertEquals(1, turns.size)
        assertTrue(turns.single().toString().contains("QUJD"))
    }

    @Test
    fun severalPhotosOnOneMessageAreAllSentBeforeTheWords() = runBlocking {
        server.enqueue(textReply("ok"))
        provider.send(request(messages = listOf(AiChatMessage("user", "two pages", imagesJpegBase64 = listOf("QUJD", "REVG")))))

        val content = nextBody()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals("QUJD", content[0].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("REVG", content[1].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("text", content[2].jsonObject["type"]!!.jsonPrimitive.content)
    }
}
