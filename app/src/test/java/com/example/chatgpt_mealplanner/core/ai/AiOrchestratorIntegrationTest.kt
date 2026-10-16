package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolCall
import org.junit.Assert.assertNull
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.repository.ChatRepositoryImpl
import com.example.chatgpt_mealplanner.core.repository.RetailRepositoryImpl
import com.example.chatgpt_mealplanner.core.service.FakeChatDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeLeftoverDao
import com.example.chatgpt_mealplanner.core.service.FakeMealDao
import com.example.chatgpt_mealplanner.core.service.FakeRetailDao
import com.example.chatgpt_mealplanner.core.service.FakeShoppingDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.MealDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.RetailShoppingDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainServiceImpl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AiOrchestratorIntegrationTest {

    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var inventoryBarcodeDao: FakeInventoryBarcodeDao
    private lateinit var mealDao: FakeMealDao
    private lateinit var leftoverDao: FakeLeftoverDao
    private lateinit var shoppingDao: FakeShoppingDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var retailDao: FakeRetailDao
    private lateinit var chatDao: FakeChatDao

    private lateinit var inventoryDomainService: InventoryDomainServiceImpl
    private lateinit var mealDomainService: MealDomainServiceImpl
    private lateinit var shoppingDomainService: ShoppingDomainServiceImpl
    private lateinit var retailShoppingDomainService: RetailShoppingDomainServiceImpl
    private lateinit var chatRepository: ChatRepositoryImpl

    private lateinit var aiToolDispatcher: AiToolDispatcher
    private lateinit var intentResolver: UserMutationIntentResolver
    private lateinit var contextBuilder: AiContextBuilder
    private lateinit var pendingMutationStore: com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore
    private lateinit var activeProposalStore: com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore

    private val milkItem = InventoryItem(
        id = "milk_1",
        canonicalIngredientId = "MILK",
        canonicalName = "Milk",
        displayName = "Whole Milk 2L",
        category = FoodCategory.FRIDGE,
        storageLocation = StorageLocation.FRIDGE,
        quantity = 2.0,
        quantityUnit = QuantityUnit.LITRE,
        quantityConfidence = QuantityConfidence.EXACT,
        status = ItemStatus.AVAILABLE
    )

    @Before
    fun setup() {
        inventoryDao = FakeInventoryDao()
        inventoryBarcodeDao = FakeInventoryBarcodeDao()
        mealDao = FakeMealDao()
        leftoverDao = FakeLeftoverDao()
        shoppingDao = FakeShoppingDao()
        stapleRuleDao = FakeStapleRuleDao()
        transactionDao = FakeTransactionDao()
        retailDao = FakeRetailDao()
        chatDao = FakeChatDao()

        pendingMutationStore = com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore(chatDao)
        activeProposalStore = com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore(chatDao)

        val runner = FakeTransactionRunner(
            fakeMealDao = mealDao,
            fakeInventoryDao = inventoryDao,
            fakeTransactionDao = transactionDao,
            fakeLeftoverDao = leftoverDao,
            fakeShoppingDao = shoppingDao,
            fakeStapleRuleDao = stapleRuleDao
        )

        chatRepository = ChatRepositoryImpl(chatDao)

        inventoryDomainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = inventoryBarcodeDao,
            transactionDao = transactionDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )

        mealDomainService = MealDomainServiceImpl(
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            transactionDao = transactionDao,
            leftoverDao = leftoverDao,
            transactionRunner = runner
        )

        shoppingDomainService = ShoppingDomainServiceImpl(
            shoppingDao = shoppingDao,
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )

        val retailRepo = RetailRepositoryImpl(retailDao)
        retailShoppingDomainService = RetailShoppingDomainServiceImpl(
            shoppingDomainService = shoppingDomainService,
            shoppingDao = shoppingDao,
            retailRepository = retailRepo,
            retailDao = retailDao
        )

        aiToolDispatcher = AiToolDispatcher(
            inventoryDao = inventoryDao,
            mealDao = mealDao,
            leftoverDao = leftoverDao,
            shoppingDao = shoppingDao,
            inventoryDomainService = inventoryDomainService,
            mealDomainService = mealDomainService,
            shoppingDomainService = shoppingDomainService,
            retailShoppingDomainService = retailShoppingDomainService,
            chatDao = chatDao,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        intentResolver = UserMutationIntentResolver()
        contextBuilder = AiContextBuilder(
            inventoryDao = inventoryDao,
            mealDao = mealDao,
            leftoverDao = leftoverDao,
            shoppingDao = shoppingDao,
            activeProposalStore = activeProposalStore,
            pendingMutationStore = pendingMutationStore
        )

        val conv = ChatConversationEntity("conv_1", "Test Chat", System.currentTimeMillis(), System.currentTimeMillis())
        runBlocking {
            chatDao.insertOrUpdateConversation(conv)
            inventoryDao.insertItem(milkItem.toEntity())
        }
    }

    @Test
    fun orchestrator_executesWriteToolWithIntentScope_andPersistsMessage() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("Connected")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_1",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "call_out_1",
                            toolName = "mark_all_inventory_for_canonical_ingredient_out",
                            argsJson = "{\"canonicalIngredientId\":\"MILK\"}"
                        )
                    )
                )
            }

            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("I have marked all Milk as out of stock.")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "no milk left")
        assertTrue(result is AiOrchestrationResult.Final)

        val responseText = (result as AiOrchestrationResult.Final).assistantText
        assertEquals("I have marked all Milk as out of stock.", responseText)

        val updatedMilk = inventoryDao.getItemById("milk_1")
        assertEquals(ItemStatus.OUT, updatedMilk?.status)
    }

    @Test
    fun readToolSuccess_continuation503_doesNotClaimActionApplied() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_read",
                    calls = listOf(
                        AiToolCall("call_1", "get_upcoming_planned_meals", "{}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Gemini busy")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "what is for dinner")
        assertTrue("Provider error without write MUST return AiOrchestrationResult.Error", result is AiOrchestrationResult.Error)
        val userFacingMsg = (result as AiOrchestrationResult.Error).userFacingMessage
        assertTrue("User facing message MUST contain provider error detail", userFacingMsg.contains("Gemini busy"))
    }

    @Test
    fun getUpcomingMeals_continuationUnavailable_returnsReadOnlyError() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_read",
                    calls = listOf(
                        AiToolCall("call_1", "get_upcoming_planned_meals", "{}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "503 Service Unavailable")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "what is for dinner")
        assertTrue(result is AiOrchestrationResult.Error)
        val userFacingMsg = (result as AiOrchestrationResult.Error).userFacingMessage
        assertTrue(userFacingMsg.contains("503 Service Unavailable"))
    }

    @Test
    fun writeToolSuccess_setsMutationCommitted() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_write",
                    calls = listOf(
                        AiToolCall("call_write_1", "mark_all_inventory_for_canonical_ingredient_out", "{\"canonicalIngredientId\":\"MILK\"}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Gemini busy")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "no milk left")
        assertTrue(result is AiOrchestrationResult.Final)

        val assistantText = (result as AiOrchestrationResult.Final).assistantText
        assertEquals("Your requested action was applied successfully, but Gemini is temporarily busy to generate a follow-up summary. Please check your screen.", assistantText)
    }

    @Test
    fun writeToolFailure_doesNotSetMutationCommitted() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_unauthorized",
                    calls = listOf(
                        AiToolCall("call_write_unauth", "cancel_planned_meal", "{\"mealId\":\"meal_99\"}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Busy")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "just asking")
        val text = when (result) {
            is AiOrchestrationResult.Final -> result.assistantText
            is AiOrchestrationResult.Error -> result.userFacingMessage
        }

        assertFalse("Unauthorized write failure must NOT claim action applied", text.contains("action was applied"))
        assertFalse("Unauthorized write failure must NOT claim applied successfully", text.contains("applied successfully"))
    }

    @Test
    fun writeAwaitingConfirmation_doesNotSetMutationCommitted() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_prep",
                    calls = listOf(
                        AiToolCall("call_prep", "prepare_mark_meal_cooked", "{\"mealId\":\"meal_1\"}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Busy")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "I cooked dinner")
        val text = when (result) {
            is AiOrchestrationResult.Final -> result.assistantText
            is AiOrchestrationResult.Error -> result.userFacingMessage
        }

        assertFalse("Confirmation-required preparation MUST NOT claim action applied until confirmed", text.contains("action was applied"))
        assertFalse("Confirmation-required preparation MUST NOT claim applied successfully", text.contains("applied successfully"))
    }

    @Test
    fun previousTurnWriteSuccess_doesNotLeakIntoNextReadTurn() = runTest {
        var turnCount = 0
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                turnCount++
                return if (turnCount == 1) {
                    AiProviderResponse.ToolCalls(
                        providerContinuationToken = "t1",
                        calls = listOf(AiToolCall("c1", "mark_all_inventory_for_canonical_ingredient_out", "{\"canonicalIngredientId\":\"MILK\"}"))
                    )
                } else {
                    AiProviderResponse.ToolCalls(
                        providerContinuationToken = "t2",
                        calls = listOf(AiToolCall("c2", "get_upcoming_planned_meals", "{}"))
                    )
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return if (turnCount == 1) {
                    AiProviderResponse.FinalResponse("Turn 1 write complete")
                } else {
                    AiProviderResponse.Error("HTTP_503", "Busy on turn 2")
                }
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        // Turn 1: Write
        val turn1Res = orchestrator.processUserMessage("conv_1", "no milk left")
        assertTrue(turn1Res is AiOrchestrationResult.Final)

        // Turn 2: Read
        val turn2Res = orchestrator.processUserMessage("conv_1", "what is for dinner")
        assertTrue("Provider error without write MUST return AiOrchestrationResult.Error", turn2Res is AiOrchestrationResult.Error)
    }

    @Test
    fun readThenWriteFailure_doesNotClaimActionApplied() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_mixed",
                    calls = listOf(
                        AiToolCall("call_r", "get_inventory_summary", "{}"),
                        AiToolCall("call_w_fail", "cancel_planned_meal", "{\"mealId\":\"invalid_meal\"}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Busy")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "check inventory and cancel meal")
        assertTrue("Provider error without committed write MUST return Error", result is AiOrchestrationResult.Error)
        val userFacingMsg = (result as AiOrchestrationResult.Error).userFacingMessage
        assertTrue(userFacingMsg.contains("Busy"))
    }

    @Test
    fun readThenWriteSuccess_continuationFailure_canClaimPartialSuccess() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_mixed_success",
                    calls = listOf(
                        AiToolCall("call_r", "get_inventory_summary", "{}"),
                        AiToolCall("call_w", "mark_all_inventory_for_canonical_ingredient_out", "{\"canonicalIngredientId\":\"MILK\"}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Busy")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "no milk left")
        val assistantText = (result as AiOrchestrationResult.Final).assistantText

        assertEquals("Your requested action was applied successfully, but Gemini is temporarily busy to generate a follow-up summary. Please check your screen.", assistantText)
    }

    @Test
    fun fallbackAfterReadTool_doesNotClaimMutationSuccess() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_read_fallback",
                    calls = listOf(
                        AiToolCall("call_r", "get_upcoming_planned_meals", "{}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "All models busy")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "what is for dinner")
        assertTrue("Cross-model fallback failure after READ MUST return Error", result is AiOrchestrationResult.Error)
        val userFacingMsg = (result as AiOrchestrationResult.Error).userFacingMessage
        assertTrue(userFacingMsg.contains("All models busy"))
    }

    @Test
    fun whatIsForDinner_failure_neverSaysActionApplied() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_dinner",
                    calls = listOf(
                        AiToolCall("call_dinner", "get_upcoming_planned_meals", "{}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Service Unavailable")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "what is for dinner")
        assertTrue("Read-only continuation error MUST return Error", result is AiOrchestrationResult.Error)
        val userFacingMsg = (result as AiOrchestrationResult.Error).userFacingMessage
        assertTrue(userFacingMsg.contains("Service Unavailable"))
    }

    @Test
    fun singleUserSend_createsExactlyOneUserChatMessageEntity() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Hello! How can I help with your meals today?")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", contextBuilder.buildContextPayload(), emptyList(), emptyList()))
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        orchestrator.processUserMessage("conv_1", "hello")

        val messages = chatRepository.getMessagesForConversation("conv_1")
        val userMessages = messages.filter { it.role == "USER" }
        assertEquals("Single send must create exactly ONE USER ChatMessageEntity", 1, userMessages.size)
        assertEquals("hello", userMessages.first().content)
        assertNull("USER ChatMessageEntity must have null modelId", userMessages.first().modelId)
    }

    @Test
    fun modelProvenance_propagatesModelIdFromProviderToDatabaseAndResult() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "prov_token",
                    calls = listOf(
                        AiToolCall("call_1", "get_recent_cooked_meals", "{}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse(
                    text = "Tool continuation complete.",
                    modelId = "gemini-3.6-flash"
                )
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "check my recent meals")

        assertTrue(result is AiOrchestrationResult.Final)
        val finalResult = result as AiOrchestrationResult.Final
        assertEquals("gemini-3.6-flash", finalResult.modelId)

        val messages = chatRepository.getMessagesForConversation("conv_1")
        val userMsg = messages.first { it.role == "USER" }
        val assistantMsg = messages.first { it.role == "ASSISTANT" }

        assertNull("USER message must have null modelId", userMsg.modelId)
        assertEquals("ASSISTANT message saved in Room DB must retain exact modelId", "gemini-3.6-flash", assistantMsg.modelId)
    }

    @Test
    fun initialDeleteRequest_doesNotMutate_andStagesConfirmation() = runTest {
        val testItem = InventoryItem(
            id = "del_item_1",
            canonicalName = "ai delete test",
            displayName = "ai delete test",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            frozen = false,
            staple = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(testItem.toEntity())

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "del_token_1",
                    calls = listOf(
                        AiToolCall("call_del_1", "delete_inventory_item", "{\"searchQuery\":\"ai delete test\"}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Are you sure you want to delete 'ai delete test'?")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_1", "delete ai delete test from inventory")
        assertTrue(result is AiOrchestrationResult.Final)

        val itemAfterTurn1 = inventoryDao.getItemById("del_item_1")
        assertEquals(ItemStatus.AVAILABLE, itemAfterTurn1?.status)

        val pending = pendingMutationStore.getPendingMutation("conv_1")
        assertTrue(pending is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem)
        val pendingDelete = pending as com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem
        assertEquals("del_item_1", pendingDelete.itemId)
        assertEquals("ai delete test", pendingDelete.targetName)
    }

    @Test
    fun confirmedDelete_executesExactlyOnce() = runTest {
        val testItem = InventoryItem(
            id = "del_item_2",
            canonicalName = "Toothpaste",
            displayName = "Toothpaste",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            frozen = false,
            staple = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(testItem.toEntity())

        pendingMutationStore.setPendingMutation(
            "conv_2",
            com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem("del_item_2", "Toothpaste")
        )

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "del_token_2",
                    calls = listOf(
                        AiToolCall("call_del_2", "delete_inventory_item", "{\"itemId\":\"del_item_2\"}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Successfully removed 'Toothpaste'.")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_2", "yes delete it")
        assertTrue(result is AiOrchestrationResult.Final)

        val itemAfterConfirm = inventoryDao.getItemById("del_item_2")
        assertEquals(ItemStatus.DISCARDED, itemAfterConfirm?.status)

        assertNull(pendingMutationStore.getPendingMutation("conv_2"))
    }

    @Test
    fun cancelledDelete_neverMutates() = runTest {
        val testItem = InventoryItem(
            id = "del_item_3",
            canonicalName = "Basmati Rice",
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            frozen = false,
            staple = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(testItem.toEntity())

        pendingMutationStore.setPendingMutation(
            "conv_3",
            com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem("del_item_3", "Basmati Rice")
        )

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Cancelled deletion.")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", contextBuilder.buildContextPayload("conv_3"), emptyList(), emptyList()))
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("conv_3", "no don't delete it")
        assertTrue(result is AiOrchestrationResult.Final)

        val item = inventoryDao.getItemById("del_item_3")
        assertEquals(ItemStatus.AVAILABLE, item?.status)

        assertNull(pendingMutationStore.getPendingMutation("conv_3"))
    }

    @Test
    fun retryUserMessage_doesNotCreateAnotherUserRow() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Hello!")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", contextBuilder.buildContextPayload("conv_retry"), emptyList(), emptyList()))
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val msgId = "user_msg_retry_1"
        orchestrator.processUserMessage("conv_retry", "delete milk", userMessageId = msgId)

        val messagesTurn1 = chatRepository.getMessagesForConversation("conv_retry").filter { it.role == "USER" }
        assertEquals(1, messagesTurn1.size)
        assertEquals(msgId, messagesTurn1.first().id)

        orchestrator.processUserMessage("conv_retry", "delete milk", userMessageId = msgId)

        val messagesRetry = chatRepository.getMessagesForConversation("conv_retry").filter { it.role == "USER" }
        assertEquals("Retrying with same userMessageId MUST NOT create duplicate USER row", 1, messagesRetry.size)
    }

    @Test
    fun sendingIdenticalTextTwice_createsTwoDistinctUserMessages() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("OK")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse = send(AiProviderRequest("c", "p", "{}", contextBuilder.buildContextPayload("conv_dup"), emptyList(), emptyList()))
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        orchestrator.processUserMessage("conv_dup", "delete milk", userMessageId = "msg_t1")
        orchestrator.processUserMessage("conv_dup", "delete milk", userMessageId = "msg_t2")

        val userMessages = chatRepository.getMessagesForConversation("conv_dup").filter { it.role == "USER" }
        assertEquals("Two distinct turns with identical text MUST create TWO USER rows", 2, userMessages.size)
        assertEquals("msg_t1", userMessages[0].id)
        assertEquals("msg_t2", userMessages[1].id)
    }

    @Test
    fun runtimeTrace_initialDelete_neverExecutes_andRequiresConfirmationThenConfirms() = runTest {
        val testItem = InventoryItem(
            id = "del_item_trace",
            canonicalName = "test",
            displayName = "test",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            frozen = false,
            staple = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(testItem.toEntity())

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "trace_token",
                    calls = listOf(
                        AiToolCall("call_trace_1", "delete_inventory_item", "{\"searchQuery\":\"test\"}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("I have removed test from your inventory.")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val turn1Result = orchestrator.processUserMessage("conv_trace", "delete test from inventory", userMessageId = "msg_t1")
        assertTrue(turn1Result is AiOrchestrationResult.Final)

        val turn1Text = (turn1Result as AiOrchestrationResult.Final).assistantText
        assertTrue("Turn 1 response MUST ask for confirmation", turn1Text.contains("Are you sure you want to delete"))
        assertFalse("Turn 1 response MUST NOT claim removed", turn1Text.contains("I have removed"))

        val itemTurn1 = inventoryDao.getItemById("del_item_trace")
        assertEquals(ItemStatus.AVAILABLE, itemTurn1?.status)

        val pendingTurn1 = pendingMutationStore.getPendingMutation("conv_trace")
        assertTrue(pendingTurn1 is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem)
        assertEquals(com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION, pendingTurn1?.state)

        val turn2Result = orchestrator.processUserMessage("conv_trace", "confirm", userMessageId = "msg_t2")
        assertTrue(turn2Result is AiOrchestrationResult.Final)

        val turn2Text = (turn2Result as AiOrchestrationResult.Final).assistantText
        assertTrue("Turn 2 response MUST confirm removal", turn2Text.contains("I have removed"))

        val itemTurn2 = inventoryDao.getItemById("del_item_trace")
        assertEquals(ItemStatus.DISCARDED, itemTurn2?.status)

        assertNull(pendingMutationStore.getPendingMutation("conv_trace"))
    }
}
