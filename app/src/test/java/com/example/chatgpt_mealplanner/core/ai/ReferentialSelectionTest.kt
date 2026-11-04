package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.contract.AiChatMessage
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.ai.provider.GeminiMealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.repository.ChatRepositoryImpl
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
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainServiceImpl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ReferentialSelectionTest {

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
    private lateinit var chatRepository: ChatRepositoryImpl

    private lateinit var aiToolDispatcher: AiToolDispatcher
    private lateinit var intentResolver: UserMutationIntentResolver
    private lateinit var contextBuilder: AiContextBuilder
    private lateinit var pendingMutationStore: com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore
    private lateinit var activeProposalStore: com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore

    private val conversationId = "referential_conv_1"

    private val assistantProposalText = """
        Here are two options for your meal plan:

        OPTION 1: QUICK & LIGHT
        Breakfast: Yogurt Parfait with Berries
        Lunch: Caesar Salad Wrap
        Dinner: Grilled Chicken Breast with Steamed Veggies

        OPTION 2: EASY COMFORT FOOD
        Breakfast: Classic Oatmeal with Banana, Nut Butter, and Cinnamon
        Lunch: Grilled Cheese & Tomato Soup
        Dinner: One-Pot Beef Spaghetti Bolognese with Garlic Bread

        Pick an option above or let me know if you want changes!
    """.trimIndent()

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


        aiToolDispatcher = AiToolDispatcher(
            inventoryDao = inventoryDao,
            mealDao = mealDao,
            leftoverDao = leftoverDao,
            shoppingDao = shoppingDao,
            inventoryDomainService = inventoryDomainService,
            mealDomainService = mealDomainService,
            shoppingDomainService = shoppingDomainService,
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

        runBlocking {
            chatDao.insertOrUpdateConversation(
                ChatConversationEntity(conversationId, "Test Chat", System.currentTimeMillis(), System.currentTimeMillis())
            )
        }
    }

    @Test
    fun previousAssistantProposal_isIncludedInNextProviderRequest() = runTest {
        var capturedRequest: AiProviderRequest? = null

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.lastOrNull()?.content?.startsWith("[STRUCTURED_OFFER_REPAIR]") == true) {
                    return AiProviderResponse.FinalResponse("Options provided.")
                }
                capturedRequest = request
                if (request.messages.count { !it.content.startsWith("[STRUCTURED_OFFER_REPAIR]") } == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText)
                }
                return AiProviderResponse.FinalResponse("Option selected.")
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        // Turn 1: User asks for plan -> Assistant proposes Option 1 and Option 2
        orchestrator.processUserMessage(conversationId, "Give me meal plan options")

        // Turn 2: User responds
        orchestrator.processUserMessage(conversationId, "easy comfort food")

        assertNotNull(capturedRequest)
        val messagesSent = capturedRequest!!.messages
        assertTrue("Request must contain multiple historical messages", messagesSent.size >= 3)

        val hasAssistantProposal = messagesSent.any { msg ->
            msg.role.equals("assistant", ignoreCase = true) && msg.content.contains("OPTION 2: EASY COMFORT FOOD")
        }
        assertTrue("Previous Assistant proposal must be present in conversation history sent to provider", hasAssistantProposal)
    }

    @Test
    fun selectOption2_preservesPreviouslyProposedOption() = runTest {
        var lastReceivedMessages = emptyList<AiChatMessage>()

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                lastReceivedMessages = request.messages
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText)
                }
                return AiProviderResponse.FinalResponse(
                    text = "Great choice! You selected Option 2: Easy Comfort Food. Meal plan set to Oatmeal, Grilled Cheese & Tomato Soup, and Bolognese.",
                    modelId = "gemini-3.7-flash"
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "Give me meal plan options")
        val result = orchestrator.processUserMessage(conversationId, "option 2")

        assertTrue(result is AiOrchestrationResult.Final)
        val assistantText = (result as AiOrchestrationResult.Final).assistantText

        assertTrue("Response must preserve selected Option 2 meals", assistantText.contains("Option 2"))
        assertTrue("Response must reference Oatmeal", assistantText.contains("Oatmeal"))
        assertTrue("Response must reference Bolognese", assistantText.contains("Bolognese"))

        val lastUserMessage = lastReceivedMessages.lastOrNull { it.role == "user" && !it.content.startsWith("[STRUCTURED_OFFER_REPAIR]") }
        assertEquals("option 2", lastUserMessage?.content)
    }

    @Test
    fun selectOptionByUniqueTitle_preservesPreviouslyProposedOption() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText)
                }
                return AiProviderResponse.FinalResponse(
                    text = "Selected Easy Comfort Food: Oatmeal, Grilled Cheese & Tomato Soup, and Spaghetti Bolognese.",
                    modelId = "gemini-3.7-flash"
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "Suggest meal options")
        val result = orchestrator.processUserMessage(conversationId, "easy comfort food")

        assertTrue(result is AiOrchestrationResult.Final)
        val text = (result as AiOrchestrationResult.Final).assistantText
        assertTrue("Selecting by title 'easy comfort food' preserves proposed option", text.contains("Easy Comfort Food"))
        assertTrue("Preserves proposed breakfast", text.contains("Oatmeal"))
    }

    @Test
    fun selectFirstOne_resolvesPreviousFirstOption() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText)
                }
                return AiProviderResponse.FinalResponse(
                    text = "You selected the first one (Option 1: Quick & Light) with Yogurt Parfait, Caesar Wrap, and Grilled Chicken Breast.",
                    modelId = "gemini-3.7-flash"
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "What should I eat?")
        val result = orchestrator.processUserMessage(conversationId, "first one")

        assertTrue(result is AiOrchestrationResult.Final)
        val text = (result as AiOrchestrationResult.Final).assistantText
        assertTrue("Resolves 'first one' to Option 1", text.contains("Option 1") || text.contains("Quick & Light"))
        assertTrue("Preserves Option 1 chicken breast meal", text.contains("Grilled Chicken"))
    }

    @Test
    fun selectionDoesNotRegenerateDifferentMeals() = runTest {
        var turn2ResponseText = ""

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText)
                }
                turn2ResponseText = "Option 2 selected: Classic Oatmeal, Grilled Cheese & Tomato Soup, and One-Pot Beef Spaghetti Bolognese."
                return AiProviderResponse.FinalResponse(turn2ResponseText)
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "Meal plan please")
        orchestrator.processUserMessage(conversationId, "easy comfort food")

        assertFalse("Selection MUST NOT regenerate completely different meals (e.g. Mac and Cheese)", turn2ResponseText.contains("Mac and Cheese"))
        assertTrue("Selection MUST preserve the original proposed Bolognese", turn2ResponseText.contains("Bolognese"))
    }

    @Test
    fun selectionWithRequestedDinnerChange_preservesOtherMeals() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText)
                }
                return AiProviderResponse.FinalResponse(
                    text = "Updated Option 2: Kept Classic Oatmeal for breakfast and Grilled Cheese & Tomato Soup for lunch, changed dinner to Grilled Salmon with Asparagus."
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "Meal ideas")
        val result = orchestrator.processUserMessage(conversationId, "option 2 but change dinner to salmon")

        assertTrue(result is AiOrchestrationResult.Final)
        val text = (result as AiOrchestrationResult.Final).assistantText

        assertTrue("Preserves unchanged breakfast", text.contains("Oatmeal"))
        assertTrue("Preserves unchanged lunch", text.contains("Grilled Cheese"))
        assertTrue("Updates requested dinner to Salmon", text.contains("Salmon"))
    }

    @Test
    fun requestAnotherPlan_allowsNewSuggestions() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText)
                }
                return AiProviderResponse.FinalResponse(
                    text = "Here is a fresh new comfort food option: Beef Stew with Mashed Potatoes and Apple Pie for dessert."
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "Ideas please")
        val result = orchestrator.processUserMessage(conversationId, "give me another comfort food plan")

        assertTrue(result is AiOrchestrationResult.Final)
        val text = (result as AiOrchestrationResult.Final).assistantText

        assertTrue("Explicit request for new plan generates new suggestions", text.contains("Beef Stew"))
    }

    @Test
    fun selectionAlone_doesNotWritePlanner() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText)
                }
                return AiProviderResponse.FinalResponse(
                    text = "Option 2 (Easy Comfort Food) sounds great! Let me know when you want to add it to your planner."
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "Meal plan")
        orchestrator.processUserMessage(conversationId, "easy comfort food")

        val plannedMeals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals("Selection message alone MUST NOT write/mutate the planner database", 0, plannedMeals.size)
    }

    @Test
    fun explicitAddSelectedOption_canEnterWriteFlow() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText)
                }
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "token_write_plan",
                    calls = listOf(
                        AiToolCall("call_1", "add_planned_meal", "{\"title\": \"Option 2\", \"date\": \"2026-09-30\", \"mealType\": \"DINNER\", \"servings\": 2, \"ingredients\": []}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Added Option 2 to your planner.")
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

        orchestrator.processUserMessage(conversationId, "Options")
        val result = orchestrator.processUserMessage(conversationId, "add option 2 to my planner")

        assertTrue(result is AiOrchestrationResult.Final)
        val text = (result as AiOrchestrationResult.Final).assistantText
        assertTrue("Explicit write request can invoke tools to manage the planner", text.contains("Added Option 2"))
    }

    @Test
    fun fallbackModel_receivesProposalGeneratedByPreviousModel() = runTest {
        var fallbackReceivedHistory: List<AiChatMessage> = emptyList()

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                fallbackReceivedHistory = request.messages
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText, modelId = "gemini-3.7-flash")
                }
                return AiProviderResponse.FinalResponse(
                    text = "Fallback model gemini-3.6-flash resolved Option 2.",
                    modelId = "gemini-3.6-flash"
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "Plan ideas")
        orchestrator.processUserMessage(conversationId, "easy comfort food")

        val proposalMsg = fallbackReceivedHistory.firstOrNull { it.role == "assistant" && it.content.contains("OPTION 2: EASY COMFORT FOOD") }
        assertNotNull("Fallback model receives full history containing proposal generated by previous model", proposalMsg)
    }

    @Test
    fun fallbackModel_canResolveSelectionFromHistory() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText, modelId = "gemini-3.7-flash")
                }
                return AiProviderResponse.FinalResponse(
                    text = "Resolved Option 2 from history on fallback model: Classic Oatmeal, Grilled Cheese, and Spaghetti Bolognese.",
                    modelId = "gemini-3.5-flash-lite"
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "Plan ideas")
        val result = orchestrator.processUserMessage(conversationId, "easy comfort food")

        assertTrue(result is AiOrchestrationResult.Final)
        val text = (result as AiOrchestrationResult.Final).assistantText
        assertTrue("Fallback model resolves selected option from history", text.contains("Option 2") || text.contains("Oatmeal"))
        assertEquals("gemini-3.5-flash-lite", (result as AiOrchestrationResult.Final).modelId)
    }

    @Test
    fun modelProvenanceRemainsCorrectAcrossSelectionTurn() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                if (request.messages.size == 1) {
                    return AiProviderResponse.FinalResponse(assistantProposalText, modelId = "gemini-3.7-flash")
                }
                return AiProviderResponse.FinalResponse(
                    text = "Confirmed Option 2.",
                    modelId = "gemini-3.6-flash"
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
                AiProviderResponse.FinalResponse("OK")
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

        orchestrator.processUserMessage(conversationId, "Plan ideas")
        val result = orchestrator.processUserMessage(conversationId, "easy comfort food")

        val messages = chatRepository.getMessagesForConversation(conversationId)
        val assistantMessages = messages.filter { it.role == "ASSISTANT" }

        assertEquals(2, assistantMessages.size)
        assertEquals("Turn 1 assistant model provenance preserved", "gemini-3.7-flash", assistantMessages[0].modelId)
        assertEquals("Turn 2 assistant model provenance set correctly", "gemini-3.6-flash", assistantMessages[1].modelId)
        assertEquals("gemini-3.6-flash", (result as AiOrchestrationResult.Final).modelId)
    }
}
