package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.contract.AiToolResult
import com.example.chatgpt_mealplanner.contract.AiToolId
import com.example.chatgpt_mealplanner.contract.ActionAuthorizationContext
import com.example.chatgpt_mealplanner.contract.AuthorizedMutationScope
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation
import com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState
import com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore
import com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore
import com.example.chatgpt_mealplanner.core.ai.provider.GeminiMealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.repository.ChatRepositoryImpl
import com.example.chatgpt_mealplanner.core.security.FakeCredentialStore
import com.example.chatgpt_mealplanner.core.service.FakeChatDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeLeftoverDao
import com.example.chatgpt_mealplanner.core.service.FakeMealDao
import com.example.chatgpt_mealplanner.core.service.FakeShoppingDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.MealDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainServiceImpl
import com.example.chatgpt_mealplanner.feature.chat.ChatUiEvent
import com.example.chatgpt_mealplanner.feature.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class EndToEndAiRecipeReliabilityTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var inventoryBarcodeDao: FakeInventoryBarcodeDao
    private lateinit var mealDao: FakeMealDao
    private lateinit var leftoverDao: FakeLeftoverDao
    private lateinit var shoppingDao: FakeShoppingDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var chatDao: FakeChatDao

    private lateinit var inventoryDomainService: InventoryDomainServiceImpl
    private lateinit var mealDomainService: MealDomainServiceImpl
    private lateinit var shoppingDomainService: ShoppingDomainServiceImpl
    private lateinit var chatRepository: ChatRepositoryImpl

    private lateinit var aiToolDispatcher: AiToolDispatcher
    private lateinit var intentResolver: UserMutationIntentResolver
    private lateinit var contextBuilder: AiContextBuilder
    private lateinit var pendingMutationStore: PendingMutationStore
    private lateinit var activeProposalStore: ActiveProposalStore
    private lateinit var geminiProvider: GeminiMealPlannerAiProvider

    @Before
    fun setup() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)

        inventoryDao = FakeInventoryDao()
        inventoryBarcodeDao = FakeInventoryBarcodeDao()
        mealDao = FakeMealDao()
        leftoverDao = FakeLeftoverDao()
        shoppingDao = FakeShoppingDao()
        stapleRuleDao = FakeStapleRuleDao()
        transactionDao = FakeTransactionDao()
        chatDao = FakeChatDao()

        pendingMutationStore = PendingMutationStore(chatDao)
        activeProposalStore = ActiveProposalStore(chatDao)

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
            inventoryDao = inventoryDao,
            mealDao = mealDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )


        aiToolDispatcher = AiToolDispatcher(
            inventoryDomainService = inventoryDomainService,
            mealDomainService = mealDomainService,
            shoppingDomainService = shoppingDomainService,
            activeProposalStore = activeProposalStore,
            pendingMutationStore = pendingMutationStore,
            chatDao = chatDao,
            inventoryDao = inventoryDao,
            leftoverDao = leftoverDao,
            shoppingDao = shoppingDao,
            mealDao = mealDao
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

        geminiProvider = GeminiMealPlannerAiProvider(
            credentialStore = FakeCredentialStore("test_api_key"),
            ioDispatcher = testDispatcher
        )

        chatDao.insertOrUpdateConversation(
            ChatConversationEntity(
                id = "default_conversation",
                title = "Pantry Assistant",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Assertion 1: Offer schema declares instructions
    @Test
    fun test1_offerSchemaDeclaresSteps() {
        val method = GeminiMealPlannerAiProvider::class.java.getDeclaredMethod("buildToolDeclarationJson", AiToolId::class.java)
        method.isAccessible = true
        val jsonObj = method.invoke(geminiProvider, AiToolId.OFFER_ADD_PLANNED_MEAL) as kotlinx.serialization.json.JsonObject

        val props = jsonObj["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("ARRAY", props["steps"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val addMeal = method.invoke(geminiProvider, AiToolId.ADD_PLANNED_MEAL) as kotlinx.serialization.json.JsonObject
        val addMealProps = addMeal["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("STRING", addMealProps["instructions"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    // Assertion 2: Required fields include title, servings, ingredients, steps
    @Test
    fun test2_requiredFieldsIncludeTitleServingsIngredientsSteps() {
        val method = GeminiMealPlannerAiProvider::class.java.getDeclaredMethod("buildToolDeclarationJson", AiToolId::class.java)
        method.isAccessible = true
        val jsonObj = method.invoke(geminiProvider, AiToolId.OFFER_ADD_PLANNED_MEAL) as kotlinx.serialization.json.JsonObject

        val requiredArray = jsonObj["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(requiredArray.contains("title"))
        assertTrue(requiredArray.contains("servings"))
        assertTrue(requiredArray.contains("ingredients"))
        assertTrue(requiredArray.contains("steps"))
    }

    // Assertion 3: Empty ingredients -> tool failure
    @Test
    fun test3_emptyIngredientsYieldsToolFailure() = runTest(testDispatcher) {
        val call = AiToolCall(
            toolCallId = "call_empty_ings",
            toolName = "offer_add_planned_meal",
            argsJson = """{"title":"Shakshuka","servings":2,"ingredients":[],"instructions":"Cook eggs in sauce"}"""
        )
        val res = aiToolDispatcher.executeToolCall("default_conversation", ActionAuthorizationContext("msg_1"), call)
        assertFalse("Tool execution must fail on empty ingredients", res.success)
        assertEquals("OFFER_INGREDIENTS_EMPTY", res.errorCode)
    }

    // Assertion 4: Blank instructions -> tool failure
    @Test
    fun test4_blankInstructionsYieldsToolFailure() = runTest(testDispatcher) {
        val call = AiToolCall(
            toolCallId = "call_blank_inst",
            toolName = "offer_add_planned_meal",
            argsJson = """{"title":"Shakshuka","servings":2,"ingredients":[{"displayName":"Eggs","quantity":2,"unit":"ITEM"}],"instructions":""}"""
        )
        val res = aiToolDispatcher.executeToolCall("default_conversation", ActionAuthorizationContext("msg_1"), call)
        assertFalse("Tool execution must fail on blank instructions", res.success)
        assertEquals("OFFER_INSTRUCTIONS_EMPTY", res.errorCode)
    }

    // Assertion 5: Invalid offer -> no success=true
    @Test
    fun test5_invalidOfferPreventsSuccessTrue() = runTest(testDispatcher) {
        val callNoTitle = AiToolCall(
            toolCallId = "call_no_title",
            toolName = "offer_add_planned_meal",
            argsJson = """{"title":"","servings":2,"ingredients":[{"displayName":"Eggs","quantity":2,"unit":"ITEM"}],"instructions":"Cook eggs"}"""
        )
        val res = aiToolDispatcher.executeToolCall("default_conversation", ActionAuthorizationContext("msg_1"), callNoTitle)
        assertFalse(res.success)
        assertEquals("OFFER_TITLE_MISSING", res.errorCode)
    }

    // Assertion 6: Complete offer -> durable AWAITING_CONFIRMATION snapshot
    @Test
    fun test6_completeOfferCreatesDurableAwaitingConfirmationSnapshot() = runTest(testDispatcher) {
        val callValid = AiToolCall(
            toolCallId = "call_valid",
            toolName = "offer_add_planned_meal",
            argsJson = """{"title":"Shakshuka","servings":2,"ingredients":[{"displayName":"Eggs","quantity":2,"unit":"ITEM"}],"instructions":"1. Heat sauce.\n2. Crack eggs."}"""
        )
        val res = aiToolDispatcher.executeToolCall("default_conversation", ActionAuthorizationContext("msg_1"), callValid)
        assertTrue(res.success)

        val pending = pendingMutationStore.getPendingMutation("default_conversation") as? PendingAiMutation.AddPlannedMeal
        assertNotNull(pending)
        assertEquals(PendingMutationState.AWAITING_CONFIRMATION, pending!!.state)
        assertEquals("Shakshuka", pending.title)
        assertEquals("1. Heat sauce.\n2. Crack eggs.", pending.instructions)
    }

    // Assertion 7: Snapshot instructions survive confirmation
    @Test
    fun test7_snapshotInstructionsSurviveConfirmation() = runTest(testDispatcher) {
        val pendingAdd = PendingAiMutation.AddPlannedMeal(
            title = "Shakshuka",
            date = "2026-10-02",
            mealType = com.example.chatgpt_mealplanner.core.model.MealType.LUNCH,
            servings = 2,
            ingredients = listOf(
                com.example.chatgpt_mealplanner.core.model.MealIngredient(
                    id = "ing_1",
                    mealId = "",
                    displayName = "Eggs",
                    plannedQuantity = 2.0,
                    plannedUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT
                )
            ),
            instructions = "1. Heat sauce.\n2. Crack eggs.",
            sourceUserMessageId = "msg_1",
            state = PendingMutationState.AWAITING_CONFIRMATION
        )
        pendingMutationStore.setPendingMutation("default_conversation", pendingAdd)

        val retrieved = pendingMutationStore.getPendingMutation("default_conversation") as? PendingAiMutation.AddPlannedMeal
        assertNotNull(retrieved)
        assertEquals("1. Heat sauce.\n2. Crack eggs.", retrieved!!.instructions)
    }

    // Assertion 8: Confirmation creates Meal.instructions
    @Test
    fun test8_confirmationCreatesMealInstructions() = runTest(testDispatcher) {
        val pendingAdd = PendingAiMutation.AddPlannedMeal(
            title = "Shakshuka",
            date = "2026-10-02",
            mealType = com.example.chatgpt_mealplanner.core.model.MealType.LUNCH,
            servings = 2,
            ingredients = listOf(
                com.example.chatgpt_mealplanner.core.model.MealIngredient(
                    id = "ing_1",
                    mealId = "",
                    displayName = "Eggs",
                    plannedQuantity = 2.0,
                    plannedUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT
                )
            ),
            instructions = "1. Heat sauce.\n2. Crack eggs.",
            sourceUserMessageId = "msg_1",
            state = PendingMutationState.AWAITING_CONFIRMATION
        )
        pendingMutationStore.setPendingMutation("default_conversation", pendingAdd)

        val confirmCall = AiToolCall(
            toolCallId = "call_confirm",
            toolName = "add_planned_meal",
            argsJson = """{"title":"Shakshuka"}"""
        )
        val authCtx = ActionAuthorizationContext("msg_2", listOf(AuthorizedMutationScope.AddPlannedMealScope("msg_2")))
        val res = aiToolDispatcher.executeToolCall("default_conversation", authCtx, confirmCall)
        assertTrue(res.success)

        val meals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        val created = meals.firstOrNull { it.meal.title == "Shakshuka" }
        assertNotNull("Created meal must exist in DB", created)
        assertEquals("1. Heat sauce.\n2. Crack eggs.", created!!.meal.instructions)
    }

    // Assertion 9: FOLLOW_UP_TO_ACTIVE_PROPOSAL missing valid offer -> bounded repair
    @Test
    fun test9_followUpIntentMissingValidOfferTriggersBoundedRepair() = runTest(testDispatcher) {
        val mockProvider = object : MealPlannerAiProvider {
            var callsCount = 0
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                callsCount++
                return if (callsCount == 1) {
                    AiProviderResponse.FinalResponse(
                        text = "Here is a recipe for Shakshuka with tomatoes and eggs.",
                        proposal = null,
                        proposedAction = null,
                        modelId = "gemini-3.6-flash"
                    )
                } else {
                    AiProviderResponse.ToolCalls(
                        providerContinuationToken = "token_repair",
                        calls = listOf(
                            AiToolCall(
                                toolCallId = "call_repaired",
                                toolName = "offer_add_planned_meal",
                                argsJson = """{"title":"Shakshuka","servings":2,"ingredients":[{"displayName":"Eggs","quantity":2,"unit":"ITEM"}],"instructions":"Cook eggs in tomato sauce"}"""
                            )
                        )
                    )
                }
            }

            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse(
                    text = "Would you like me to add Shakshuka to your plan?",
                    proposal = null,
                    proposedAction = null,
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

        // Set up active proposal so intent classifier produces FOLLOW_UP_TO_ACTIVE_PROPOSAL
        activeProposalStore.setActiveProposal("default_conversation", com.example.chatgpt_mealplanner.contract.MealProposal(
            title = "Previous Meal",
            mealType = "LUNCH",
            suggestedDate = "2026-10-02",
            servings = 2,
            ingredients = emptyList(),
            reasoningSummary = "Offered Meal"
        ))

        val result = orchestrator.processUserMessage("default_conversation", "give me a new recipe for lunch")
        assertTrue(result is AiOrchestrationResult.Final)

        val pending = pendingMutationStore.getPendingMutation("default_conversation") as? PendingAiMutation.AddPlannedMeal
        assertNotNull("Repair must create valid pending mutation", pending)
        assertEquals("Cook eggs in tomato sauce", pending!!.instructions)
    }

    // Assertion 10: Repair returning prose only -> explicit recoverable failure
    @Test
    fun test10_repairReturningProseOnlyYieldsExplicitRecoverableFailure() = runTest(testDispatcher) {
        val proseOnlyProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse(
                    text = "Here is a recipe for Shakshuka with tomatoes and eggs.",
                    proposal = null,
                    proposedAction = null,
                    modelId = "gemini-3.6-flash"
                )
            }

            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse(text = "Prose only", proposal = null, proposedAction = null)
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = proseOnlyProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("default_conversation", "give me a new recipe for lunch")
        assertTrue("Repair returning prose only must yield Error result", result is AiOrchestrationResult.Error)
        val err = result as AiOrchestrationResult.Error
        assertEquals("INVALID_STRUCTURED_OFFER", err.code)
    }

    // Assertion 11: Exhausted provider failure -> Chat failure state
    @Test
    fun test11_exhaustedProviderFailureYieldsChatFailureState() = runTest(testDispatcher) {
        val errorProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.Error("EMPTY_RESPONSE", "Gemini API returned an empty response.")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("EMPTY_RESPONSE", "Gemini API returned an empty response.")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = errorProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
        val collectJob = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.UpdateMessageText("make me a recipe"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("make me a recipe", state.failedUserMessage)
        assertNotNull(state.errorMessage)
        collectJob.cancel()
    }

    // Assertion 12: Retry reuses original user text
    @Test
    fun test12_retryReusesOriginalUserText() = runTest(testDispatcher) {
        var sendCount = 0
        val toggleProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                sendCount++
                return if (sendCount == 1) {
                    AiProviderResponse.Error("EMPTY_RESPONSE", "Error on first attempt")
                } else {
                    AiProviderResponse.FinalResponse(text = "Here is your recipe", proposal = null, proposedAction = null)
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse(text = "Done", proposal = null, proposedAction = null)
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = toggleProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
        val collectJob = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.UpdateMessageText("make me a shakshuka"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testScheduler.advanceUntilIdle()

        assertEquals("make me a shakshuka", viewModel.uiState.value.failedUserMessage)

        viewModel.onEvent(ChatUiEvent.RetryLastFailedMessage)
        testScheduler.advanceUntilIdle()

        assertNull("failedUserMessage must be cleared on retry success", viewModel.uiState.value.failedUserMessage)
        assertEquals(2, sendCount)
        collectJob.cancel()
    }

    @Test
    fun failedConfirmationCanBeRetriedWithoutLosingPendingActionOrDuplicatingUserMessage() = runTest(testDispatcher) {
        val itemId = "retry-confirmation-item"
        pendingMutationStore.setPendingMutation(
            "default_conversation",
            PendingAiMutation.DeleteInventoryItem(itemId = itemId, targetName = "Milk")
        )

        val noNetworkProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest) =
                AiProviderResponse.Error("UNEXPECTED_AI_CALL", "Confirmation should stay app-owned")

            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) =
                AiProviderResponse.Error("UNEXPECTED_AI_CALL", "Confirmation should stay app-owned")
        }
        val orchestrator = AiMealPlannerOrchestrator(
            provider = noNetworkProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )
        val viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
        val collectJob = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.UpdateMessageText("yes"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testScheduler.advanceUntilIdle()

        val failedState = viewModel.uiState.value
        assertEquals(com.example.chatgpt_mealplanner.feature.chat.ChatRequestState.FAILED_RETRYABLE, failedState.requestState)
        assertTrue("Failed confirmation must expose Retry", failedState.isRetryAvailable)
        assertNotNull("Failed confirmation must preserve its pending action", pendingMutationStore.getPendingMutation("default_conversation"))

        inventoryDao.insertItem(
            com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity(
                id = itemId,
                canonicalName = "milk",
                displayName = "Milk",
                category = com.example.chatgpt_mealplanner.core.model.FoodCategory.FRIDGE,
                storageLocation = com.example.chatgpt_mealplanner.core.model.StorageLocation.FRIDGE,
                quantity = 1.0,
                quantityUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT,
                quantityConfidence = com.example.chatgpt_mealplanner.core.model.QuantityConfidence.EXACT,
                status = com.example.chatgpt_mealplanner.core.model.ItemStatus.AVAILABLE,
                frozen = false,
                staple = false,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        viewModel.onEvent(ChatUiEvent.RetryLastFailedMessage)
        testScheduler.advanceUntilIdle()

        val succeededState = viewModel.uiState.value
        assertEquals(com.example.chatgpt_mealplanner.feature.chat.ChatRequestState.SUCCEEDED, succeededState.requestState)
        assertFalse("Successful retry must clear Retry", succeededState.isRetryAvailable)
        assertNull("Successful retry must clear the pending action", pendingMutationStore.getPendingMutation("default_conversation"))
        assertEquals(com.example.chatgpt_mealplanner.core.model.ItemStatus.DISCARDED, inventoryDao.getItemById(itemId)?.status)
        assertEquals(
            "Retry must reuse the original confirmation message instead of inserting a duplicate",
            1,
            chatRepository.getMessagesForConversation("default_conversation").count { it.role == "USER" }
        )
        collectJob.cancel()
    }

    // Assertion 13: Repeated Retry tap cannot launch concurrent requests
    @Test
    fun test13_repeatedRetryTapCannotLaunchConcurrentRequests() = runTest(testDispatcher) {
        var callCounter = 0
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                callCounter++
                kotlinx.coroutines.delay(100)
                return AiProviderResponse.Error("RATE_LIMIT", "Rate limit")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("RATE_LIMIT", "Rate limit")
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

        val viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
        val collectJob = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.UpdateMessageText("test concurrency"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testScheduler.advanceUntilIdle()

        val initialCalls = callCounter

        viewModel.onEvent(ChatUiEvent.RetryLastFailedMessage)
        viewModel.onEvent(ChatUiEvent.RetryLastFailedMessage) // Double tap
        testScheduler.advanceUntilIdle()

        assertEquals("Only one additional call should be dispatched on double tap", initialCalls + 1, callCounter)
        collectJob.cancel()
    }

    // Assertion 14: Retry before any committed write is safe
    @Test
    fun test14_retryBeforeAnyCommittedWriteIsSafe() = runTest(testDispatcher) {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.Error("SERVER_ERROR", "Server error")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("SERVER_ERROR", "Server error")
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

        val initialMeals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        val result = orchestrator.processUserMessage("default_conversation", "add shakshuka to plan")
        assertTrue(result is AiOrchestrationResult.Error)

        val postMeals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals("No meals should be created when request fails before write", initialMeals.size, postMeals.size)
    }

    // Assertion 15: Committed-write + continuation failure does not replay write
    @Test
    fun test15_committedWriteContinuationFailureDoesNotReplayWrite() = runTest(testDispatcher) {
        val writeThenErrorProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_write",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "call_write",
                            toolName = "add_planned_meal",
                            argsJson = """{"title":"Shakshuka","date":"2026-10-02","mealType":"LUNCH","servings":2,"ingredients":[{"displayName":"Eggs","quantity":2,"unit":"ITEM"}],"instructions":"Cook eggs"}"""
                        )
                    )
                )
            }

            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.Error("HTTP_503", "Service unavailable during continuation")
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = writeThenErrorProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("default_conversation", "add shakshuka to plan")

        assertTrue("When write is committed before provider error, orchestrator returns Final fallback text", result is AiOrchestrationResult.Final)
        val text = (result as AiOrchestrationResult.Final).assistantText
        assertTrue(text.contains("applied successfully") || text.contains("check your screen"))

        val meals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals(1, meals.size)
    }

    // Race Safety & State Machine Specific Regression Tests
    @Test
    fun sendMessage_doesNotExposeRetryWhileRequestInFlight() = runTest(testDispatcher) {
        val suspendingProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                kotlinx.coroutines.delay(5000)
                return AiProviderResponse.FinalResponse("Done")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("Done")
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = suspendingProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
        val collectJob = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.UpdateMessageText("what is in my fridge?"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testScheduler.runCurrent()

        val inFlightState = viewModel.uiState.value
        assertTrue("isLoading must be true while in flight", inFlightState.isLoading)
        assertEquals(com.example.chatgpt_mealplanner.feature.chat.ChatRequestState.IN_FLIGHT, inFlightState.requestState)
        assertFalse("isRetryAvailable must be false while in flight", inFlightState.isRetryAvailable)
        assertNull("failedUserMessage must be null while in flight", inFlightState.failedUserMessage)

        testScheduler.advanceUntilIdle()
        val finalState = viewModel.uiState.value
        assertFalse(finalState.isLoading)
        assertEquals(com.example.chatgpt_mealplanner.feature.chat.ChatRequestState.SUCCEEDED, finalState.requestState)
        assertFalse(finalState.isRetryAvailable)
        collectJob.cancel()
    }

    @Test
    fun providerFallbackInProgress_doesNotExposeRetry() = runTest(testDispatcher) {
        var attempts = 0
        val multiAttemptProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                attempts++
                kotlinx.coroutines.delay(1000)
                if (attempts == 1) {
                    kotlinx.coroutines.delay(1000) // Simulates fallback model chain attempt
                    return AiProviderResponse.FinalResponse("Fallback model succeeded")
                }
                return AiProviderResponse.FinalResponse("Succeeded")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("Done")
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = multiAttemptProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
        val collectJob = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.UpdateMessageText("test fallback"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        val duringPrimaryState = viewModel.uiState.value
        assertTrue(duringPrimaryState.isLoading)
        assertFalse("Retry must remain hidden during primary model call", duringPrimaryState.isRetryAvailable)

        testScheduler.advanceTimeBy(1000)
        testScheduler.runCurrent()
        val duringFallbackState = viewModel.uiState.value
        assertTrue(duringFallbackState.isLoading)
        assertFalse("Retry must remain hidden during model fallback", duringFallbackState.isRetryAvailable)

        testScheduler.advanceUntilIdle()
        val endState = viewModel.uiState.value
        assertEquals(com.example.chatgpt_mealplanner.feature.chat.ChatRequestState.SUCCEEDED, endState.requestState)
        assertFalse(endState.isRetryAvailable)
        collectJob.cancel()
    }

    // Requirement 11 & 12 Complete Test Suite (Tests 1 - 20)

    @Test
    fun newRecipe_supersedesExistingActiveProposal() = runTest(testDispatcher) {
        val proposal = com.example.chatgpt_mealplanner.contract.MealProposal(
            title = "Creamy Spinach & Chickpea Curry",
            mealType = "DINNER",
            servings = 2,
            ingredients = emptyList(),
            reasoningSummary = "Stale curry proposal"
        )
        activeProposalStore.setActiveProposal("default_conversation", proposal)

        val intentClass = com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClassifier.classify(
            userMessageText = "give me a new recipe for dinner",
            pendingMutation = null,
            hasActiveProposal = true
        )

        assertEquals(
            com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.NEW_READ_OR_PROPOSAL_REQUEST,
            intentClass
        )
    }

    @Test
    fun anotherRecipe_supersedesExistingActiveProposal() = runTest(testDispatcher) {
        val proposal = com.example.chatgpt_mealplanner.contract.MealProposal(
            title = "Curry",
            mealType = "DINNER",
            servings = 2,
            ingredients = emptyList(),
            reasoningSummary = "Stale curry"
        )
        activeProposalStore.setActiveProposal("default_conversation", proposal)

        val intentClass = com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClassifier.classify(
            userMessageText = "suggest another recipe",
            pendingMutation = null,
            hasActiveProposal = true
        )

        assertEquals(
            com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.NEW_READ_OR_PROPOSAL_REQUEST,
            intentClass
        )
    }

    @Test
    fun actualModification_remainsFollowUp() = runTest(testDispatcher) {
        val proposal = com.example.chatgpt_mealplanner.contract.MealProposal(
            title = "Curry",
            mealType = "DINNER",
            servings = 2,
            ingredients = emptyList(),
            reasoningSummary = "Active curry"
        )
        activeProposalStore.setActiveProposal("default_conversation", proposal)

        val intentClass = com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClassifier.classify(
            userMessageText = "make that vegetarian",
            pendingMutation = null,
            hasActiveProposal = true
        )

        assertEquals(
            com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.FOLLOW_UP_TO_ACTIVE_PROPOSAL,
            intentClass
        )
    }

private val milkAllergyAndNoCoriander = com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto(
        householdSize = 4,
        allergies = listOf("Milk"),
        diets = listOf("Vegetarian"),
        dislikes = listOf("coriander"),
        equipment = listOf("air fryer"),
        skillLevel = "Beginner"
    )

    private fun dispatcherWith(preferences: com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto) = AiToolDispatcher(
        inventoryDomainService = inventoryDomainService,
        mealDomainService = mealDomainService,
        shoppingDomainService = shoppingDomainService,
        activeProposalStore = activeProposalStore,
        pendingMutationStore = pendingMutationStore,
        chatDao = chatDao,
        inventoryDao = inventoryDao,
        leftoverDao = leftoverDao,
        shoppingDao = shoppingDao,
        mealDao = mealDao,
        cookingPreferences = { preferences }
    )

    @Test
    fun allergenInARecipe_isSentBackAndNeverBecomesACard() = runTest(testDispatcher) {
        val call = AiToolCall("c1", "offer_add_planned_meal", offerArgs("double cream", "Stir in the cream."))

        val result = dispatcherWith(milkAllergyAndNoCoriander)
            .executeToolCall("default_conversation", ActionAuthorizationContext("m1"), call)

        assertFalse(result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("conflicts with the Milk allergy"))
        assertNull(pendingMutationStore.getPendingMutation("default_conversation"))
    }

    @Test
    fun allergenSuggestedAsASwap_isSentBack() = runTest(testDispatcher) {
        val call = AiToolCall(
            "c1",
            "offer_add_planned_meal",
            """{"title":"Hearty Dish","servings":2,"ingredients":[{"displayName":"olive oil","quantity":15,"unit":"MILLILITRE","substitute":"butter"}],"steps":[{"instruction":"Heat the oil."}]}"""
        )

        val result = dispatcherWith(milkAllergyAndNoCoriander)
            .executeToolCall("default_conversation", ActionAuthorizationContext("m1"), call)

        assertFalse(result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("'butter' conflicts with the Milk allergy"))
    }

    @Test
    fun allergenSwapInAMealPlan_warnsButDoesNotBlock() = runTest(testDispatcher) {
        val validated = RecipeValidator(inventoryDao, mealDao) { milkAllergyAndNoCoriander }.validateProposal(
            com.example.chatgpt_mealplanner.contract.MealProposal(
                title = "Hearty Dish",
                ingredients = listOf(com.example.chatgpt_mealplanner.contract.ProposedIngredient(displayName = "olive oil", quantity = 15.0, unit = "MILLILITRE", substitute = "butter")),
                reasoningSummary = ""
            )
        )

        assertTrue(validated.canBeSaved())
        assertTrue(validated.warnings.toString(), validated.warnings.single().contains("Don't use the suggested swap"))
    }

    @Test
    fun meatInAVegetarianHousehold_isSentBack() = runTest(testDispatcher) {
        val call = AiToolCall("c1", "offer_add_planned_meal", offerArgs("chicken breast", "Roast the chicken."))

        val result = dispatcherWith(milkAllergyAndNoCoriander)
            .executeToolCall("default_conversation", ActionAuthorizationContext("m1"), call)

        assertFalse(result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("Vegetarian diet"))
    }

    @Test
    fun dislikedIngredient_keepsTheCardAndTellsGemini() = runTest(testDispatcher) {
        val call = AiToolCall("c1", "offer_add_planned_meal", offerArgs("fresh coriander", "Scatter over the coriander."))

        val result = dispatcherWith(milkAllergyAndNoCoriander)
            .executeToolCall("default_conversation", ActionAuthorizationContext("m1"), call)

        assertTrue(result.success)
        assertTrue(result.payloadJson, result.payloadJson.contains("dislikes"))
    }

    @Test
    fun directSaveWithAnAllergen_isRefused() = runTest(testDispatcher) {
        val call = AiToolCall(
            "c1",
            "add_planned_meal",
            """{"title":"Creamy Pasta","servings":2,"date":"2026-11-10","mealType":"DINNER","ingredients":[{"displayName":"double cream","quantity":200,"unit":"MILLILITRE"}],"instructions":"Stir in the cream."}"""
        )
        val authorised = ActionAuthorizationContext("m1", listOf(AuthorizedMutationScope.AddPlannedMealScope("m1")))

        val result = dispatcherWith(milkAllergyAndNoCoriander).executeToolCall("default_conversation", authorised, call)

        assertFalse(result.errorMessage ?: "no error", result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("conflicts with the Milk allergy"))
        assertTrue(mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31").none { it.meal.title == "Creamy Pasta" })
    }

    @Test
    fun mealPlanWithAnAllergen_cannotBeSaved() = runTest(testDispatcher) {
        val validator = RecipeValidator(inventoryDao, mealDao) { milkAllergyAndNoCoriander }
        val meal = com.example.chatgpt_mealplanner.contract.MealProposal(
            title = "Creamy Pasta",
            ingredients = listOf(com.example.chatgpt_mealplanner.contract.ProposedIngredient(displayName = "double cream", quantity = 200.0, unit = "MILLILITRE")),
            reasoningSummary = ""
        )

        val validated = validator.validateProposal(meal)

        assertFalse(validated.canBeSaved())
        assertTrue(validated.issues.toString(), validated.issues.single().contains("Milk allergy"))
    }

    private fun planMeal(title: String, ingredient: String) = com.example.chatgpt_mealplanner.contract.MealProposal(
        title = title,
        ingredients = listOf(com.example.chatgpt_mealplanner.contract.ProposedIngredient(displayName = ingredient, quantity = 200.0, unit = "GRAM")),
        reasoningSummary = ""
    )

    private val chickenPlan = listOf(planMeal("Chicken Stew", "chicken thighs"), planMeal("Rice Bowl", "basmati rice"))
    private val vegetarianPlan = listOf(planMeal("Bean Stew", "butter beans"), planMeal("Rice Bowl", "basmati rice"))

    /** Sends each reply in order for a plan request, and returns the result with the requests made. */
    private suspend fun planRequest(vararg replies: AiProviderResponse): Pair<AiOrchestrationResult.Final, List<AiProviderRequest>> {
        val requests = mutableListOf<AiProviderRequest>()
        val orchestrator = AiMealPlannerOrchestrator(
            provider = object : MealPlannerAiProvider {
                override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                    requests += request
                    return replies[requests.size - 1]
                }
                override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) =
                    AiProviderResponse.FinalResponse("Done")
            },
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao) { milkAllergyAndNoCoriander },
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )
        val result = orchestrator.processUserMessage("default_conversation", "create a meal plan with dinners for the next 3 days")
        return (result as AiOrchestrationResult.Final) to requests
    }

    @Test
    fun mealPlanThatBreaksADiet_getsOneCorrection() = runTest(testDispatcher) {
        val (result, requests) = planRequest(
            AiProviderResponse.FinalResponse("Chicken plan", mealPlan = chickenPlan),
            AiProviderResponse.FinalResponse("Vegetarian plan", mealPlan = vegetarianPlan)
        )

        assertEquals(vegetarianPlan, result.mealPlan)
        assertEquals(2, requests.size)
        assertTrue(requests[1].availableTools.isEmpty())
        val correction = requests[1].messages.last().content
        assertTrue(correction, correction.contains("'chicken thighs' conflicts with the Vegetarian diet."))
    }

    @Test
    fun mealPlanStillBreakingADiet_isNotRetriedAgain() = runTest(testDispatcher) {
        val (result, requests) = planRequest(
            AiProviderResponse.FinalResponse("Chicken plan", mealPlan = chickenPlan),
            AiProviderResponse.FinalResponse("Chicken plan again", mealPlan = chickenPlan)
        )

        assertEquals(chickenPlan, result.mealPlan)
        assertEquals(2, requests.size)
    }

    @Test
    fun failedMealPlanCorrection_keepsTheOriginalPlan() = runTest(testDispatcher) {
        val (result, requests) = planRequest(
            AiProviderResponse.FinalResponse("Chicken plan", mealPlan = chickenPlan),
            AiProviderResponse.Error("RATE_LIMITED", "Busy")
        )

        assertEquals(chickenPlan, result.mealPlan)
        assertEquals("Chicken plan", result.assistantText)
        assertEquals(2, requests.size)
    }

    @Test
    fun mealPlanWithinTheRules_takesOneRequest() = runTest(testDispatcher) {
        val (result, requests) = planRequest(AiProviderResponse.FinalResponse("Plan", mealPlan = vegetarianPlan))

        assertEquals(vegetarianPlan, result.mealPlan)
        assertEquals(1, requests.size)
    }

    @Test
    fun geminiContext_carriesPreferencesAndHouseholdSize() = runTest(testDispatcher) {
        val builder = AiContextBuilder(
            inventoryDao = inventoryDao,
            mealDao = mealDao,
            leftoverDao = leftoverDao,
            shoppingDao = shoppingDao,
            activeProposalStore = activeProposalStore,
            pendingMutationStore = pendingMutationStore,
            cookingPreferences = { milkAllergyAndNoCoriander }
        )

        val json = builder.buildContextPayloadJson("default_conversation")

        listOf("\"householdSize\":4", "Milk", "Vegetarian", "coriander", "air fryer", "Beginner").forEach {
            assertTrue(it, json.contains(it))
        }
    }

    private fun offerArgs(ingredient: String, method: String, quantity: Int = 200) =
        """{"title":"Hearty Dish","servings":2,"ingredients":[{"displayName":"$ingredient","quantity":$quantity,"unit":"GRAM"}],"steps":[{"instruction":"$method"}]}"""

    /** Runs offers in order, as if Gemini were replying to each tool result, and returns what it was told. */
    private suspend fun offerInTurn(vararg offers: String): Pair<List<AiToolResult>, AiOrchestrationResult> {
        val results = mutableListOf<AiToolResult>()
        var next = 0
        fun nextOffer() = AiProviderResponse.ToolCalls("t", listOf(AiToolCall("c$next", "offer_add_planned_meal", offers[next++])))
        val orchestrator = AiMealPlannerOrchestrator(
            provider = object : MealPlannerAiProvider {
                override suspend fun send(request: AiProviderRequest): AiProviderResponse =
                    if (next < offers.size) nextOffer() else AiProviderResponse.FinalResponse("Here is a recipe")
                override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                    results += request.toolResults
                    return if (next < offers.size) nextOffer() else AiProviderResponse.FinalResponse("Here you go")
                }
            },
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )
        return results to orchestrator.processUserMessage("default_conversation", "suggest a recipe for dinner")
    }

    private suspend fun requestsForOffers(userMessage: String, vararg offers: String): Int {
        var requests = 0
        var next = 0
        val orchestrator = AiMealPlannerOrchestrator(
            provider = object : MealPlannerAiProvider {
                private fun reply(): AiProviderResponse {
                    requests++
                    return if (next < offers.size) {
                        AiProviderResponse.ToolCalls("t", listOf(AiToolCall("c$next", "offer_add_planned_meal", offers[next++])), modelId = "test-model")
                    } else {
                        AiProviderResponse.FinalResponse(text = "Here you go", modelId = "test-model")
                    }
                }
                override suspend fun send(request: AiProviderRequest) = reply()
                override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = reply()
            },
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )
        val result = orchestrator.processUserMessage("default_conversation", userMessage) as AiOrchestrationResult.Final
        assertNotNull(result.validatedProposal)
        assertEquals("test-model", result.modelId)
        return requests
    }

    @Test
    fun cleanRecipe_takesOneRequest() = runTest(testDispatcher) {
        assertEquals(1, requestsForOffers("suggest a recipe for dinner", offerArgs("rice", "Boil the rice.")))
    }

    @Test
    fun queriedRecipe_getsOneCorrectionThenTheCardStands() = runTest(testDispatcher) {
        val queried = offerArgs("rice", "Boil the rice in salted water.")

        assertEquals(2, requestsForOffers("suggest a recipe for dinner", queried, offerArgs("rice", "Boil the rice.")))
    }

    @Test
    fun recipeStillQueriedAfterItsCorrection_isAcceptedWithoutMoreRequests() = runTest(testDispatcher) {
        val queried = offerArgs("rice", "Boil the rice in salted water.")

        assertEquals(2, requestsForOffers("suggest a recipe for dinner", queried, queried, queried))
    }

    @Test
    fun replacementMeal_stillAsksForTheChangeSummary() = runTest(testDispatcher) {
        assertEquals(2, requestsForOffers("Prepare a replacement recipe for this meal-change request", offerArgs("rice", "Boil the rice.")))
    }

    @Test
    fun vagueIngredient_isSentBackForGeminiToFix() = runTest(testDispatcher) {
        val (told, result) = offerInTurn(
            offerArgs("herbs", "Stir in the herbs."),
            offerArgs("dried oregano", "Stir in the oregano.")
        )

        assertFalse(told.single().success)
        assertTrue(told.single().errorMessage!!, told.single().errorMessage!!.contains("'herbs' is too vague"))
        assertEquals("dried oregano", (result as AiOrchestrationResult.Final).validatedProposal!!.proposal.ingredients.single().displayName)
    }

    @Test
    fun vagueIngredient_isNeverAcceptedHoweverOftenItIsOffered() = runTest(testDispatcher) {
        val (told, _) = offerInTurn(offerArgs("herbs", "Stir in the herbs."), offerArgs("herbs", "Stir in the herbs."))

        assertTrue(told.none { it.success })
        assertNull(pendingMutationStore.getPendingMutation("default_conversation"))
    }

    @Test
    fun ingredientMissingFromTheMethod_keepsTheCardAndTellsGemini() = runTest(testDispatcher) {
        val (told, result) = offerInTurn(offerArgs("cheddar", "Boil the potatoes."))

        assertTrue(told.single().success)
        assertTrue(told.single().payloadJson, told.single().payloadJson.contains("not in the method"))
        assertNotNull((result as AiOrchestrationResult.Final).validatedProposal)
    }

    @Test
    fun correctedOfferAfterAQuery_replacesTheCard() = runTest(testDispatcher) {
        val (_, result) = offerInTurn(
            offerArgs("cheddar", "Boil the potatoes."),
            offerArgs("cheddar", "Boil the potatoes, then fold in the cheddar.")
        )

        val steps = (result as AiOrchestrationResult.Final).validatedProposal!!.proposal.steps
        assertEquals("Boil the potatoes, then fold in the cheddar.", steps.single().instruction)
    }

    @Test
    fun implausibleAmount_isQueried() = runTest(testDispatcher) {
        val (told, _) = offerInTurn(offerArgs("rice", "Boil the rice.", quantity = 5000))

        assertTrue(told.single().payloadJson, told.single().payloadJson.contains("looks like too much"))
    }

    @Test
    fun ingredientNames_matchTheMethodThroughPluralsAndDescriptions() {
        fun queries(name: String, method: String) = com.example.chatgpt_mealplanner.core.ai.validation.RecipeOfferChecks.queries(
            com.example.chatgpt_mealplanner.contract.MealProposal(
                title = "T",
                ingredients = listOf(com.example.chatgpt_mealplanner.contract.ProposedIngredient(displayName = name, quantity = 1.0, unit = "UNIT")),
                reasoningSummary = ""
            ),
            method
        )

        assertTrue(queries("tomatoes", "Chop the tomato.").isEmpty())
        assertTrue(queries("onion", "Fry the onions.").isEmpty())
        assertTrue(queries("Extra virgin olive oil", "Heat the oil.").isEmpty())
        assertTrue(queries("water", "Bring to the boil.").isEmpty())
        assertEquals(1, queries("fresh basil", "Bring to the boil.").size)
    }

    @Test
    fun cleanRecipe_isNotQueried() = runTest(testDispatcher) {
        val result = aiToolDispatcher.executeToolCall(
            "default_conversation",
            ActionAuthorizationContext("m1"),
            AiToolCall("c1", "offer_add_planned_meal", offerArgs("rice", "Boil the rice."))
        )

        assertTrue(result.success)
        assertFalse(result.payloadJson, result.payloadJson.contains("queries"))
    }

    @Test
    fun invalidRecipe_cannotBeSaved() = runTest(testDispatcher) {
        val validator = RecipeValidator(inventoryDao, mealDao)
        val proposal = com.example.chatgpt_mealplanner.contract.MealProposal(
            title = "T",
            ingredients = listOf(com.example.chatgpt_mealplanner.contract.ProposedIngredient(displayName = "oil", quantity = 10.0, unit = "MILLILITRE")),
            reasoningSummary = ""
        )

        assertFalse(validator.validateProposal(proposal).canBeSaved())
        assertTrue(
            validator.validateProposal(
                proposal.copy(ingredients = listOf(proposal.ingredients.single().copy(displayName = "olive oil")))
            ).canBeSaved()
        )
    }

    private val fullOfferArgs = """{"title":"Shakshuka","servings":2,"prepMinutes":10,"cookMinutes":25,"whyThisSuits":"Uses your tomatoes.","equipment":["Deep frying pan"],
        "ingredients":[{"displayName":"Eggs","quantity":4,"unit":"UNIT"},{"displayName":"Ground cumin","quantity":2,"unit":"GRAM","measure":"1 tsp","substitute":"smoked paprika"},{"displayName":"Coriander","quantity":5,"unit":"GRAM","optional":true}],
        "steps":[{"instruction":"Fry 1 tsp ground cumin over a medium heat until fragrant.","durationMinutes":2},{"instruction":"Poach the eggs in the sauce until the whites set, then top with coriander.","durationMinutes":8}]}"""

    private suspend fun offeredProposal(pending: Boolean): com.example.chatgpt_mealplanner.contract.ValidatedMealProposal? {
        val orchestrator = AiMealPlannerOrchestrator(
            provider = object : MealPlannerAiProvider {
                override suspend fun send(request: AiProviderRequest) = AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t",
                    calls = listOf(AiToolCall("c1", "offer_add_planned_meal", fullOfferArgs))
                )
                override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) =
                    AiProviderResponse.FinalResponse("Here you go")
            },
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )
        val result = orchestrator.processUserMessage("default_conversation", "suggest a recipe for dinner")
        return if (pending) orchestrator.restorePendingMealProposal("default_conversation")
        else (result as AiOrchestrationResult.Final).validatedProposal
    }

    private fun assertFullOfferDetails(proposal: com.example.chatgpt_mealplanner.contract.MealProposal) {
        assertEquals(35, proposal.totalMinutes())
        assertEquals("Uses your tomatoes.", proposal.reasoningSummary)
        assertEquals(listOf("Deep frying pan"), proposal.equipment)
        assertEquals(listOf(2, 8), proposal.steps.map { it.durationMinutes })
        val cumin = proposal.ingredients.single { it.displayName == "Ground cumin" }
        assertEquals("1 tsp", cumin.measure)
        assertEquals("smoked paprika", cumin.substitute)
        assertEquals(2.0, cumin.quantity, 0.0)
        assertEquals("GRAM", cumin.unit)
        assertTrue(proposal.ingredients.single { it.displayName == "Coriander" }.optional)
    }

    @Test
    fun recipeCard_keepsEveryDetailOfTheOffer() = runTest(testDispatcher) {
        assertFullOfferDetails(offeredProposal(pending = false)!!.proposal)
    }

    @Test
    fun recipeDetails_surviveTheAppBeingClosed() = runTest(testDispatcher) {
        assertFullOfferDetails(offeredProposal(pending = true)!!.proposal)
    }

    @Test
    fun recipeCardWithoutATime_doesNotInventOne() {
        val proposal = com.example.chatgpt_mealplanner.contract.MealProposal(title = "Toast", ingredients = emptyList(), reasoningSummary = "")
        assertNull(proposal.totalMinutes())
    }

    @Test
    fun savedMeal_keepsTimesEquipmentStepsAndSwaps() = runTest(testDispatcher) {
        assertEquals(
            """
            Prep 10 min • Cook 25 min • Equipment: Deep frying pan
            1. Fry 1 tsp ground cumin over a medium heat until fragrant. (2 min)
            2. Poach the eggs in the sauce until the whites set, then top with coriander. (8 min)
            Swap Ground cumin for smoked paprika
            """.trimIndent(),
            offeredProposal(pending = false)!!.proposal.savedInstructions()
        )
    }

    @Test
    fun savedMeal_withOnlyStepsLooksTheSameAsBefore() {
        val proposal = com.example.chatgpt_mealplanner.contract.MealProposal(
            title = "Toast",
            ingredients = emptyList(),
            steps = listOf(com.example.chatgpt_mealplanner.contract.RecipeStep(2, "Butter it"), com.example.chatgpt_mealplanner.contract.RecipeStep(1, "Toast it")),
            reasoningSummary = ""
        )
        assertEquals("1. Toast it\n2. Butter it", proposal.savedInstructions())
    }

    @Test
    fun systemPrompt_teachesCookingAndDoesNotForceUkTermsEverywhere() = runTest(testDispatcher) {
        val sent = mutableListOf<AiProviderRequest>()
        orchestratorReplying(sent, "You have milk.").processUserMessage("default_conversation", "what is in my fridge?")
        val prompt = sent.single().systemPrompt

        listOf("RECIPE QUALITY MANDATE", "FOOD SAFETY", "piping hot", "low blood glucose", "strict rule").forEach {
            assertTrue(it, prompt.contains(it))
        }
        assertFalse(prompt.contains("Use plain, specific UK grocery names"))
    }

    private fun orchestratorReplying(sent: MutableList<AiProviderRequest>, reply: String) = AiMealPlannerOrchestrator(
        provider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                sent.add(request)
                return AiProviderResponse.FinalResponse(reply)
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) =
                AiProviderResponse.FinalResponse("Done")
        },
        contextBuilder = contextBuilder,
        toolDispatcher = aiToolDispatcher,
        recipeValidator = RecipeValidator(inventoryDao, mealDao),
        intentResolver = intentResolver,
        chatRepository = chatRepository,
        pendingMutationStore = pendingMutationStore,
        activeProposalStore = activeProposalStore
    )

    private suspend fun assertAnsweredWithoutRepair(userMessage: String, reply: String) {
        val sent = mutableListOf<AiProviderRequest>()

        val result = orchestratorReplying(sent, reply).processUserMessage("default_conversation", userMessage)

        assertEquals(userMessage, 1, sent.size)
        assertTrue(userMessage, result is AiOrchestrationResult.Final)
        assertEquals(userMessage, reply, (result as AiOrchestrationResult.Final).assistantText)
    }

    @Test
    fun adviceMentioningARecipe_isAnsweredAsAdvice() = runTest(testDispatcher) {
        assertAnsweredWithoutRepair("how long does this recipe keep?", "This recipe keeps for 3 days in the fridge.")
    }

    @Test
    fun questionsWithoutARecipeRequest_keepTheirAnswer() = runTest(testDispatcher) {
        assertAnsweredWithoutRepair("what is in my fridge?", "You have **milk**, and this method of storing it is fine.")
    }

    @Test
    fun clarifyingQuestionBeforeARecipe_isShownToTheUser() = runTest(testDispatcher) {
        assertAnsweredWithoutRepair("suggest a pizza recipe", "Which toppings would you like on your pizza?")
    }

    @Test
    fun clarifyingQuestionFollowedByMoreText_isStillShown() = runTest(testDispatcher) {
        assertAnsweredWithoutRepair(
            "suggest a pizza recipe with toppings of my choice",
            "To get this right, what toppings would you prefer? You currently have mature cheddar in the fridge."
        )
    }

    @Test
    fun fullRecipeWrittenAsText_isConvertedKeepingTheDraft() = runTest(testDispatcher) {
        val sent = mutableListOf<AiProviderRequest>()
        val draft = "Lemon Chicken\nIngredients: chicken, lemon\nMethod: roast it."

        orchestratorReplying(sent, draft).processUserMessage("default_conversation", "show me something nice to try")

        assertEquals(2, sent.size)
        assertEquals("offer_add_planned_meal", sent[1].forcedToolName)
        val assistantDraft = sent[1].messages[sent[1].messages.size - 2]
        assertEquals("assistant", assistantDraft.role)
        assertEquals(draft, assistantDraft.content)
        assertTrue(sent[1].messages.last().content.contains("keep that same dish"))
    }

    @Test
    fun recipeRequestsAnsweredInProse_stillGetACardAttempt() = runTest(testDispatcher) {
        listOf(
            "give me a recipe for lunch",
            "what can I cook tonight?",
            "any new dinner ideas?",
            "Please prepare a replacement recipe for this meal-change request"
        ).forEach { userMessage ->
            val sent = mutableListOf<AiProviderRequest>()
            orchestratorReplying(sent, "A nice tomato pasta would work.").processUserMessage("default_conversation", userMessage)
            assertEquals(userMessage, "offer_add_planned_meal", sent.getOrNull(1)?.forcedToolName)
        }
    }

    @Test
    fun followUpMessage_canSeeTheRecipeCardShownBefore() = runTest(testDispatcher) {
        val sent = mutableListOf<AiProviderRequest>()
        val provider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                sent.add(request)
                return if (sent.size == 1) {
                    AiProviderResponse.ToolCalls(
                        providerContinuationToken = "t",
                        calls = listOf(
                            AiToolCall("c1", "offer_add_planned_meal", """{"title":"Shakshuka","servings":2,"ingredients":[{"displayName":"Eggs","quantity":4,"unit":"UNIT"}],"instructions":"Poach the eggs in the sauce."}""")
                        )
                    )
                } else {
                    AiProviderResponse.FinalResponse("Add more chilli.")
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) =
                AiProviderResponse.FinalResponse("Here you go")
        }
        val orchestrator = AiMealPlannerOrchestrator(
            provider = provider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        orchestrator.processUserMessage("default_conversation", "suggest a recipe for dinner")
        orchestrator.processUserMessage("default_conversation", "make it spicier")

        val assistantTurns = sent[1].messages.filter { it.role == "assistant" }
        assertEquals(1, assistantTurns.size)
        val card = assistantTurns.single().content
        assertTrue(card, card.contains("Shakshuka"))
        assertTrue(card, card.contains("Eggs: 4.0 UNIT"))
        assertTrue(card, card.contains("Poach the eggs in the sauce."))
        assertTrue(sent[1].messages.none { it.content.isBlank() })
    }

        @Test
    fun structuredRepair_setsForcedOfferTool() = runTest(testDispatcher) {
        val capturedRequests = mutableListOf<AiProviderRequest>()
        var callCount = 0
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                capturedRequests.add(request)
                callCount++
                return if (callCount == 1) {
                    AiProviderResponse.FinalResponse("Here is a recipe for Shakshuka")
                } else {
                    AiProviderResponse.ToolCalls(
                        providerContinuationToken = "token1",
                        calls = listOf(
                            AiToolCall("call_repair", "offer_add_planned_meal", """{"title":"Shakshuka","servings":2,"ingredients":[{"displayName":"Eggs","quantity":2,"unit":"ITEM"}],"instructions":"Cook eggs"}""")
                        )
                    )
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("Would you like to add Shakshuka?")
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

        orchestrator.processUserMessage("default_conversation", "suggest a recipe for dinner")

        assertEquals(2, capturedRequests.size)
        assertNull("Initial request must NOT force tool name", capturedRequests[0].forcedToolName)
        assertEquals("offer_add_planned_meal", capturedRequests[1].forcedToolName)
    }

    @Test
    fun repair_textOnlyHttp200_isFailure() = runTest(testDispatcher) {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Here is a recipe for pasta.")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("Done")
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

        val result = orchestrator.processUserMessage("default_conversation", "suggest a recipe")
        assertTrue("Repair returning HTTP 200 text-only must fail", result is AiOrchestrationResult.Error)
        assertEquals("INVALID_STRUCTURED_OFFER", (result as AiOrchestrationResult.Error).code)
    }

    @Test
    fun repair_wrongTool_isFailure() = runTest(testDispatcher) {
        var callCount = 0
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                callCount++
                return if (callCount == 1) {
                    AiProviderResponse.FinalResponse("Here is a recipe")
                } else {
                    AiProviderResponse.ToolCalls(
                        providerContinuationToken = "token_wrong",
                        calls = listOf(
                            AiToolCall("call_wrong", "get_upcoming_planned_meals", "{}")
                        )
                    )
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("Done")
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

        val result = orchestrator.processUserMessage("default_conversation", "suggest a recipe")
        assertTrue("Repair returning wrong tool must fail", result is AiOrchestrationResult.Error)
        assertEquals("INVALID_STRUCTURED_OFFER", (result as AiOrchestrationResult.Error).code)
    }

    @Test
    fun repair_validOffer_createsPendingSnapshot() = runTest(testDispatcher) {
        var callCount = 0
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                callCount++
                return if (callCount == 1) {
                    AiProviderResponse.FinalResponse("Here is a recipe for Curry")
                } else {
                    AiProviderResponse.ToolCalls(
                        providerContinuationToken = "token_repair",
                        calls = listOf(
                            AiToolCall("call_repair", "offer_add_planned_meal", """{"title":"Curry","servings":2,"ingredients":[{"displayName":"Onion","quantity":1,"unit":"ITEM"}],"instructions":"Heat 15 ml oil..."}""")
                        )
                    )
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("Done")
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

        val result = orchestrator.processUserMessage("default_conversation", "suggest a recipe")
        assertTrue(result is AiOrchestrationResult.Final)

        val pending = pendingMutationStore.getPendingMutation("default_conversation") as? PendingAiMutation.AddPlannedMeal
        assertNotNull(pending)
        assertEquals("Curry", pending?.title)
        assertEquals("Heat 15 ml oil...", pending?.instructions)
        assertEquals(PendingMutationState.AWAITING_CONFIRMATION, pending?.state)
    }

    @Test
    fun validOffer_chatProposalDerivedFromPendingSnapshot_deliberateDriftIntegrationTest() = runTest(testDispatcher) {
        val driftProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_drift",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "call_offer_1",
                            toolName = "offer_add_planned_meal",
                            argsJson = """{"title":"Test Curry","servings":2,"ingredients":[{"displayName":"onion","quantity":1,"unit":"ITEM"}],"instructions":"Heat 15 ml oil over medium heat. Add the onion..."}"""
                        )
                    )
                )
            }

            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse(
                    text = "Recipe:\n- 2 onions\nMethod: Sauté onions gently...",
                    proposal = com.example.chatgpt_mealplanner.contract.MealProposal(
                        title = "Test Curry",
                        ingredients = emptyList(),
                        reasoningSummary = "Extracted from recipe suggestion"
                    ),
                    proposedAction = null
                )
            }
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = driftProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val result = orchestrator.processUserMessage("default_conversation", "give me a recipe for dinner")
        assertTrue(result is AiOrchestrationResult.Final)
        val finalResult = result as AiOrchestrationResult.Final
        val assistantText = finalResult.assistantText

        // The markdown/title-only parser can yield an empty proposal, but the card and Save action
        // must be rebuilt from the authoritative structured offer captured by the tool dispatcher.
        val cardProposal = finalResult.validatedProposal?.proposal
        assertNotNull("Valid structured offer must still produce a complete meal card", cardProposal)
        assertEquals("Test Curry", cardProposal?.title)
        assertEquals(1, cardProposal?.ingredients?.size)
        assertEquals("onion", cardProposal?.ingredients?.firstOrNull()?.displayName)
        assertEquals(1.0, cardProposal?.ingredients?.firstOrNull()?.quantity ?: 0.0, 0.001)

        // Requirement 10 & 12: Displayed proposal derived from pending snapshot (says "onion — 1", not provider prose "2 onions")
        assertTrue("Chat display MUST say 1 from snapshot, ignoring provider prose '2 onions'", assistantText.contains("onion — 1"))
        assertFalse("Provider prose '2 onions' MUST NOT override structured snapshot", assistantText.contains("2 onions"))

        // Requirement 11: Displayed instructions equal pending snapshot instructions ("Heat 15 ml oil...")
        assertTrue("Chat display instructions MUST use snapshot instructions", assistantText.contains("Heat 15 ml oil over medium heat. Add the onion..."))
        assertFalse("Provider prose 'Sauté onions gently' MUST NOT override structured instructions", assistantText.contains("Sauté onions gently"))

        // Requirement 13 & 14 & 15: Typed confirmation points to the card's Save meal action, saves
        // nothing, and the restored card still carries the structured snapshot values.
        val confirmResult = orchestrator.processUserMessage("default_conversation", "yes")
        assertTrue(confirmResult is AiOrchestrationResult.Final)
        assertTrue((confirmResult as AiOrchestrationResult.Final).assistantText.contains("Save meal"))
        assertEquals(0, mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31").size)

        val restoredProposal = confirmResult.validatedProposal?.proposal
        assertEquals("Test Curry", restoredProposal?.title)
        assertEquals("Heat 15 ml oil over medium heat. Add the onion...", restoredProposal?.steps?.singleOrNull()?.instruction)
        val restoredIngredient = restoredProposal?.ingredients?.singleOrNull()
        assertEquals("onion", restoredIngredient?.displayName)
        assertEquals(1.0, restoredIngredient?.quantity ?: 0.0, 0.001)
        assertEquals("UNIT", restoredIngredient?.unit)
    }

    @Test
    fun typedYesAfterProcessDeath_restoresPendingSnapshotWithoutSaving() = runTest(testDispatcher) {
        val pending = PendingAiMutation.AddPlannedMeal(
            title = "Durable Shakshuka",
            date = "2026-10-02",
            mealType = com.example.chatgpt_mealplanner.core.model.MealType.LUNCH,
            servings = 2,
            ingredients = listOf(
                com.example.chatgpt_mealplanner.core.model.MealIngredient(
                    id = "ing_durable_1",
                    mealId = "",
                    displayName = "Eggs",
                    plannedQuantity = 2.0,
                    plannedUnit = com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT
                )
            ),
            instructions = "Cook eggs in tomato sauce.",
            state = PendingMutationState.AWAITING_CONFIRMATION
        )
        pendingMutationStore.setPendingMutation("default_conversation", pending)

        // Simulate process death: ActiveProposalStore is empty
        activeProposalStore.clearActiveProposal("default_conversation")
        assertNull(activeProposalStore.getActiveProposal("default_conversation"))

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest) = AiProviderResponse.FinalResponse("No-op")
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("No-op")
        }

        val newOrchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val confirmResult = newOrchestrator.processUserMessage("default_conversation", "yes")
        assertTrue(confirmResult is AiOrchestrationResult.Final)

        assertEquals(0, mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31").size)
        assertNotNull(pendingMutationStore.getPendingMutation("default_conversation"))

        val restoredProposal = (confirmResult as AiOrchestrationResult.Final).validatedProposal?.proposal
        assertEquals("Durable Shakshuka", restoredProposal?.title)
        assertEquals("Cook eggs in tomato sauce.", restoredProposal?.steps?.singleOrNull()?.instruction)
    }

    @Test
    fun retryHiddenWhileForcedRepairInFlight() = runTest(testDispatcher) {
        var callCount = 0
        val repairHoldingProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                callCount++
                return if (callCount == 1) {
                    AiProviderResponse.FinalResponse("Here is a recipe")
                } else {
                    kotlinx.coroutines.delay(2000)
                    AiProviderResponse.Error("REPAIR_TIMEOUT", "Repair delayed")
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("Done")
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = repairHoldingProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
        val collectJob = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.UpdateMessageText("suggest a recipe"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(100)
        val inFlightState = viewModel.uiState.value
        assertTrue(inFlightState.isLoading)
        assertFalse("Retry MUST be hidden while forced repair is in flight", inFlightState.isRetryAvailable)

        testScheduler.advanceUntilIdle()
        collectJob.cancel()
    }

    @Test
    fun failedForcedRepairTransitionsToFailedRetryableOnlyAfterTermination() = runTest(testDispatcher) {
        val errorRepairProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Here is a text-only recipe")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("Done")
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = errorRepairProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
        val collectJob = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.UpdateMessageText("suggest a recipe"))
        viewModel.onEvent(ChatUiEvent.SendMessage)

        testScheduler.advanceUntilIdle()
        val finalState = viewModel.uiState.value
        assertFalse(finalState.isLoading)
        assertEquals(com.example.chatgpt_mealplanner.feature.chat.ChatRequestState.FAILED_RETRYABLE, finalState.requestState)
        assertTrue(finalState.isRetryAvailable)
        assertEquals("suggest a recipe", finalState.retainedUserMessage)
        collectJob.cancel()
    }

    @Test
    fun successfulForcedRepairNeverExposesRetry() = runTest(testDispatcher) {
        var callCount = 0
        val repairSuccessProvider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                callCount++
                return if (callCount == 1) {
                    AiProviderResponse.FinalResponse("Recipe idea")
                } else {
                    AiProviderResponse.ToolCalls(
                        providerContinuationToken = "token_repair",
                        calls = listOf(
                            AiToolCall("call_repair", "offer_add_planned_meal", """{"title":"Shakshuka","servings":2,"ingredients":[{"displayName":"Eggs","quantity":2,"unit":"ITEM"}],"instructions":"Cook eggs"}""")
                        )
                    )
                }
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest) = AiProviderResponse.FinalResponse("Done")
        }

        val orchestrator = AiMealPlannerOrchestrator(
            provider = repairSuccessProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        val viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
        val recordedStates = mutableListOf<com.example.chatgpt_mealplanner.feature.chat.ChatUiState>()
        val collectJob = launch { viewModel.uiState.collect { recordedStates.add(it) } }
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.UpdateMessageText("suggest a recipe"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testScheduler.advanceUntilIdle()

        val failedEmissions = recordedStates.filter { it.isRetryAvailable || it.requestState == com.example.chatgpt_mealplanner.feature.chat.ChatRequestState.FAILED_RETRYABLE }
        assertTrue("Successful forced repair turn MUST NEVER expose Retry state", failedEmissions.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun test16_promptForbidsOfferingAutomaticMealShoppingGeneration() {
        val orchestrator = AiMealPlannerOrchestrator(
            provider = geminiProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )
        val orchestratorClass = AiMealPlannerOrchestrator::class.java
        val field = orchestratorClass.getDeclaredField("SYSTEM_PROMPT")
        field.isAccessible = true
        val prompt = field.get(orchestrator) as String

        assertTrue(prompt.contains("AUTOMATED SHOPPING RECONCILIATION INVARIANT"))
        assertTrue(prompt.contains("Do NOT offer \"Would you like me to generate your shopping list?\""))
        assertFalse("Old prompt phrase 'add ingredients to shopping list' must be removed", prompt.contains("'add ingredients to shopping list'"))
    }

    // Assertion 17: Explicit manual shopping request remains supported
    @Test
    fun test17_explicitManualShoppingRequestRemainsSupported() = runTest(testDispatcher) {
        val manualCall = AiToolCall(
            toolCallId = "call_manual_shop",
            toolName = "add_manual_shopping_item",
            argsJson = """{"displayName":"Kitchen Towels","quantity":1.0,"unit":"PACK"}"""
        )
        val authCtx = ActionAuthorizationContext("msg_1", listOf(AuthorizedMutationScope.AddManualShoppingScope("msg_1")))
        val res = aiToolDispatcher.executeToolCall("default_conversation", authCtx, manualCall)
        assertTrue("Explicit manual shopping request must succeed", res.success)
    }
}
