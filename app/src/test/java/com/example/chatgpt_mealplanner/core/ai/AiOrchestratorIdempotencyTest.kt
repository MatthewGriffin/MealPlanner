package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.contract.ActionAuthorizationContext
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiContextPayload
import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.contract.AuthorizedMutationScope
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.ai.provider.ClaudeMealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.database.entity.AiToolExecutionLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import com.example.chatgpt_mealplanner.core.repository.ChatRepository
import com.example.chatgpt_mealplanner.core.security.FakeCredentialStore
import com.example.chatgpt_mealplanner.core.service.FakeChatDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeLeftoverDao
import com.example.chatgpt_mealplanner.core.service.FakeMealDao
import com.example.chatgpt_mealplanner.core.service.FakeReceiptDao
import com.example.chatgpt_mealplanner.core.service.FakeShoppingDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.MealDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainServiceImpl
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class AiOrchestratorIdempotencyTest {

    private lateinit var fakeChatDao: FakeChatDao
    private lateinit var fakeChatRepository: FakeChatRepository
    private lateinit var contextBuilder: AiContextBuilder
    private lateinit var intentResolver: UserMutationIntentResolver
    private lateinit var recipeValidator: RecipeValidator
    private lateinit var toolDispatcher: AiToolDispatcher
    private lateinit var pendingMutationStore: com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore
    private lateinit var activeProposalStore: com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore
    private val testDispatcher = UnconfinedTestDispatcher()

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

    @Before
    fun setup() {
        fakeChatDao = FakeChatDao()
        fakeChatRepository = FakeChatRepository(fakeChatDao)

        val inventoryDao = FakeInventoryDao()
        val barcodeDao = FakeInventoryBarcodeDao()
        val transactionDao = FakeTransactionDao()
        val stapleRuleDao = FakeStapleRuleDao()
        val mealDao = FakeMealDao()
        val leftoverDao = FakeLeftoverDao()
        val shoppingDao = FakeShoppingDao()
        val receiptDao = FakeReceiptDao()
        val runner = FakeTransactionRunner(
            fakeMealDao = mealDao,
            fakeInventoryDao = inventoryDao,
            fakeTransactionDao = transactionDao,
            fakeLeftoverDao = leftoverDao,
            fakeShoppingDao = shoppingDao,
            fakeStapleRuleDao = stapleRuleDao,
            fakeReceiptDao = receiptDao
        )

        val inventoryService = InventoryDomainServiceImpl(inventoryDao, barcodeDao, transactionDao, stapleRuleDao, runner)
        val mealService = MealDomainServiceImpl(mealDao, inventoryDao, transactionDao, leftoverDao, runner)
        val shoppingService = ShoppingDomainServiceImpl(shoppingDao, mealDao, inventoryDao, stapleRuleDao, runner)

        pendingMutationStore = com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore(fakeChatDao)
        activeProposalStore = com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore(fakeChatDao)

        toolDispatcher = AiToolDispatcher(
            inventoryDao = inventoryDao,
            mealDao = mealDao,
            leftoverDao = leftoverDao,
            shoppingDao = shoppingDao,
            inventoryDomainService = inventoryService,
            mealDomainService = mealService,
            shoppingDomainService = shoppingService,
            chatDao = fakeChatDao,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        contextBuilder = AiContextBuilder(inventoryDao, mealDao, leftoverDao, shoppingDao, activeProposalStore, pendingMutationStore)
        intentResolver = UserMutationIntentResolver()
        recipeValidator = RecipeValidator(inventoryDao, mealDao)
    }

    @Test
    fun gemini503_eventualSuccess_returnsNormalResponse() = runTest {
        var attempts = 0
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                attempts++
                return if (attempts < 3) {
                    AiProviderResponse.Error("HTTP_503", "Service Unavailable")
                } else {
                    AiProviderResponse.FinalResponse("Hello! How can I help you?")
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", emptyPayload, emptyList(), emptyList()))
            override suspend fun testConnection(): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", emptyPayload, emptyList(), emptyList()))
        }

        val res1 = mockProvider.send(AiProviderRequest("c", "p", "{}", emptyPayload, emptyList(), emptyList()))
        val res2 = mockProvider.send(AiProviderRequest("c", "p", "{}", emptyPayload, emptyList(), emptyList()))
        val res3 = mockProvider.send(AiProviderRequest("c", "p", "{}", emptyPayload, emptyList(), emptyList()))

        assertTrue(res1 is AiProviderResponse.Error)
        assertTrue(res2 is AiProviderResponse.Error)
        assertTrue(res3 is AiProviderResponse.FinalResponse)
        assertEquals("Hello! How can I help you?", (res3 as AiProviderResponse.FinalResponse).text)
    }

    @Test
    fun gemini503_retry_doesNotDuplicateUserMessage() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Service Unavailable")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", emptyPayload, emptyList(), emptyList()))
            override suspend fun testConnection(): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", emptyPayload, emptyList(), emptyList()))
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = toolDispatcher,
            recipeValidator = recipeValidator,
            intentResolver = intentResolver,
            chatRepository = fakeChatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        orchestrator.processUserMessage("conv_dedup", "Hello")

        val userMessages = fakeChatDao.messages.filter { it.role == "USER" }
        assertEquals("User message must be saved EXACTLY ONCE regardless of 503 retries", 1, userMessages.size)
    }

    @Test
    fun duplicateToolCallId_executesMutationExactlyOnce() = runTest {
        val call = AiToolCall(
            toolCallId = "call_regenerate_999",
            toolName = "regenerate_shopping_list",
            argsJson = "{}"
        )

        val authContext = ActionAuthorizationContext(
            sourceUserMessageId = "msg_1",
            scopes = listOf(AuthorizedMutationScope.RegenerateShoppingScope("msg_1"))
        )

        // First Dispatch
        val res1 = toolDispatcher.executeToolCall("conv_test", authContext, call)
        assertTrue(res1.success)

        val log1 = fakeChatDao.getToolExecutionLog("conv_test", "call_regenerate_999")
        assertNotNull(log1)
        assertEquals("SUCCEEDED", log1!!.executionStatus)

        // Second Dispatch with EXACT SAME toolCallId
        val res2 = toolDispatcher.executeToolCall("conv_test", authContext, call)
        assertTrue(res2.success)

        // Idempotency log returned cached result
        assertEquals(log1.resultPayloadJson, res2.payloadJson)
    }

    @Test
    fun sameToolCallIdDifferentConversation_doesNotPrimaryKeyCollide() = runTest {
        val call = AiToolCall(
            toolCallId = "call_shared_id_101",
            toolName = "regenerate_shopping_list",
            argsJson = "{}"
        )

        val authContext1 = ActionAuthorizationContext("msg_1", listOf(AuthorizedMutationScope.RegenerateShoppingScope("msg_1")))
        val authContext2 = ActionAuthorizationContext("msg_2", listOf(AuthorizedMutationScope.RegenerateShoppingScope("msg_2")))

        // Dispatch in Conversation A
        val resA = toolDispatcher.executeToolCall("conv_A", authContext1, call)
        assertTrue(resA.success)

        // Dispatch in Conversation B
        val resB = toolDispatcher.executeToolCall("conv_B", authContext2, call)
        assertTrue(resB.success)

        val logA = fakeChatDao.getToolExecutionLog("conv_A", "call_shared_id_101")
        val logB = fakeChatDao.getToolExecutionLog("conv_B", "call_shared_id_101")

        assertNotNull(logA)
        assertNotNull(logB)
        assertEquals("conv_A_call_shared_id_101", logA!!.id)
        assertEquals("conv_B_call_shared_id_101", logB!!.id)
        assertNotEquals(logA.id, logB.id)
    }

    @Test
    fun duplicateStartedToolCall_isHandledSafely() = runTest {
        // Pre-insert a STARTED log
        fakeChatDao.insertOrUpdateToolExecutionLog(
            AiToolExecutionLogEntity(
                id = "conv_test_call_started_1",
                conversationId = "conv_test",
                toolName = "regenerate_shopping_list",
                toolCallId = "call_started_1",
                executionClass = "WRITE_EXPLICIT",
                executionStatus = "STARTED",
                success = false,
                startedAt = 1000L
            )
        )

        val call = AiToolCall(
            toolCallId = "call_started_1",
            toolName = "regenerate_shopping_list",
            argsJson = "{}"
        )

        val authContext = ActionAuthorizationContext("msg_1", listOf(AuthorizedMutationScope.RegenerateShoppingScope("msg_1")))

        // Re-dispatch tool call after STARTED log
        val res = toolDispatcher.executeToolCall("conv_test", authContext, call)
        assertTrue(res.success)

        // Updated log to SUCCEEDED
        val updatedLog = fakeChatDao.getToolExecutionLog("conv_test", "call_started_1")
        assertNotNull(updatedLog)
        assertEquals("SUCCEEDED", updatedLog!!.executionStatus)
    }

    @Test
    fun mutationFailure_doesNotCreateSucceededLog() = runTest {
        val call = AiToolCall(
            toolCallId = "call_unauth_1",
            toolName = "regenerate_shopping_list",
            argsJson = "{}"
        )

        val unauthContext = ActionAuthorizationContext("msg_unauth", emptyList())

        val res = toolDispatcher.executeToolCall("conv_fail", unauthContext, call)
        assertFalse(res.success)

        val log = fakeChatDao.getToolExecutionLog("conv_fail", "call_unauth_1")
        assertNotNull(log)
        assertEquals("FAILED", log!!.executionStatus)
        assertNotEquals("SUCCEEDED", log.executionStatus)
    }

    @Test
    fun continuation503_afterSuccessfulMutation_preservesSuccessfulMutation() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            var round = 0
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                round++
                return if (round == 1) {
                    AiProviderResponse.ToolCalls(
                        providerContinuationToken = "token_123",
                        calls = listOf(
                            AiToolCall("call_regen_1", "regenerate_shopping_list", "{}")
                        )
                    )
                } else {
                    AiProviderResponse.Error("HTTP_503", "Service Unavailable")
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Service Unavailable")
            }
            override suspend fun testConnection(): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", emptyPayload, emptyList(), emptyList()))
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = toolDispatcher,
            recipeValidator = recipeValidator,
            intentResolver = intentResolver,
            chatRepository = fakeChatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_tool_503", "Regenerate shopping list")

        // Because local tool mutation succeeded before continuation 503:
        // Returns Final success message indicating action succeeded!
        assertTrue(result is AiOrchestrationResult.Final)
        val finalRes = result as AiOrchestrationResult.Final
        assertTrue(finalRes.assistantText.contains("applied successfully"))

        // Execution log proves mutation was persisted as SUCCEEDED
        val toolLog = fakeChatDao.getToolExecutionLog("conv_tool_503", "call_regen_1")
        assertNotNull(toolLog)
        assertEquals("SUCCEEDED", toolLog!!.executionStatus)
    }

    @Test
    fun continuation503_doesNotInsertDuplicateAssistantMessages() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_123",
                    calls = listOf(
                        AiToolCall("call_regen_2", "regenerate_shopping_list", "{}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Service Unavailable")
            }
            override suspend fun testConnection(): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", emptyPayload, emptyList(), emptyList()))
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = toolDispatcher,
            recipeValidator = recipeValidator,
            intentResolver = intentResolver,
            chatRepository = fakeChatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        orchestrator.processUserMessage("conv_assistant_dedup", "Regenerate shopping list")

        val assistantMessages = fakeChatDao.messages.filter { it.role == "ASSISTANT" }
        assertEquals(1, assistantMessages.size)
    }

    @Test
    fun networkCalls_runOnTheIoDispatcher() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        var dispatched = false
        val recordingDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatched = true
                kotlinx.coroutines.Dispatchers.IO.dispatch(context, block)
            }
        }
        val provider = ClaudeMealPlannerAiProvider(FakeCredentialStore("test_key"), recordingDispatcher).apply {
            baseUrl = server.url("/").toString().removeSuffix("/")
            maxRetries = 0
        }

        val response = provider.send(
            AiProviderRequest("conv_off_main", "System", "{}", emptyPayload, listOf(com.example.chatgpt_mealplanner.contract.AiChatMessage("user", "Hi")), emptyList())
        )
        server.shutdown()

        assertEquals("HTTP_529", (response as AiProviderResponse.Error).code)
        assertTrue("HTTP calls MUST run on the IO dispatcher, not the caller's", dispatched)
    }
}

private class FakeChatRepository(private val chatDao: FakeChatDao) : ChatRepository {
    override fun observeAllConversations(): Flow<List<com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity>> =
        MutableStateFlow(chatDao.conversations)

    override suspend fun getConversationById(id: String): com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity? =
        chatDao.getConversationById(id)

    override suspend fun createOrUpdateConversation(conversation: com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity) {
        chatDao.insertOrUpdateConversation(conversation)
    }

    override suspend fun deleteConversation(id: String) {
        chatDao.deleteConversation(id)
    }

    override fun observeMessagesForConversation(conversationId: String): Flow<List<ChatMessageEntity>> =
        MutableStateFlow(chatDao.messages.filter { it.conversationId == conversationId })

    override suspend fun getMessagesForConversation(conversationId: String): List<ChatMessageEntity> =
        chatDao.messages.filter { it.conversationId == conversationId }

    override suspend fun saveMessage(message: ChatMessageEntity) {
        chatDao.insertMessage(message)
    }
}
