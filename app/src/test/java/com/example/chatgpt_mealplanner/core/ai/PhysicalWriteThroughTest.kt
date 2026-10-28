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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PhysicalWriteThroughTest {

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

    private val conversationId = "write_through_conv_1"

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
                ChatConversationEntity(conversationId, "Write Test", System.currentTimeMillis(), System.currentTimeMillis())
            )
        }
    }

    @Test
    fun selectedProposal_addToPlanner_invokesRealWriteTool() = runTest {
        val invokedTools = mutableListOf<String>()

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t1",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "call_add_meal",
                            toolName = "add_planned_meal",
                            argsJson = "{\"title\":\"One-Pot Beef Spaghetti Bolognese\",\"date\":\"2026-09-30\",\"mealType\":\"DINNER\",\"servings\":2,\"ingredients\":[{\"displayName\":\"Spaghetti\",\"quantity\":200,\"unit\":\"GRAM\"}]}"
                        )
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                invokedTools.addAll(request.toolResults.map { it.toolName })
                return AiProviderResponse.FinalResponse("Added One-Pot Beef Spaghetti Bolognese to your planner.")
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

        val result = orchestrator.processUserMessage(conversationId, "add option 2 to my planner")

        assertTrue(result is AiOrchestrationResult.Final)
        assertTrue("add_planned_meal must be invoked", invokedTools.contains("add_planned_meal"))

        val meals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals("Planned meal must be physically persisted in database", 1, meals.size)
        assertEquals("One-Pot Beef Spaghetti Bolognese", meals.first().meal.title)
    }

    @Test
    fun selectedProposal_addIngredients_invokesRealShoppingWrite() = runTest {
        val invokedTools = mutableListOf<String>()

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t_shop",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "call_regen_shop",
                            toolName = "regenerate_shopping_list",
                            argsJson = "{}"
                        )
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                invokedTools.addAll(request.toolResults.map { it.toolName })
                return AiProviderResponse.FinalResponse("Shopping list regenerated.")
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

        val result = orchestrator.processUserMessage(conversationId, "add ingredients to my shopping list")

        assertTrue(result is AiOrchestrationResult.Final)
        assertTrue("regenerate_shopping_list tool must be invoked", invokedTools.contains("regenerate_shopping_list"))
    }

    @Test
    fun compoundPlannerAndShoppingRequest_executesBothDomains() = runTest {
        val executedTools = mutableListOf<String>()
        var round = 0

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                round = 1
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t_round1",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "c_meal",
                            toolName = "add_planned_meal",
                            argsJson = "{\"title\":\"One-Pot Beef Spaghetti Bolognese\",\"date\":\"2026-09-30\",\"mealType\":\"DINNER\",\"servings\":2,\"ingredients\":[{\"displayName\":\"Spaghetti\",\"quantity\":200,\"unit\":\"GRAM\"}]}"
                        )
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                executedTools.addAll(request.toolResults.map { it.toolName })
                if (round == 1) {
                    round = 2
                    return AiProviderResponse.ToolCalls(
                        providerContinuationToken = "t_round2",
                        calls = listOf(
                            AiToolCall(
                                toolCallId = "c_shop",
                                toolName = "regenerate_shopping_list",
                                argsJson = "{}"
                            )
                        )
                    )
                }
                return AiProviderResponse.FinalResponse("Added Bolognese to planner and generated shopping list.")
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

        val result = orchestrator.processUserMessage(conversationId, "add it to the meal plan and add the ingredients to the shopping list")

        assertTrue(result is AiOrchestrationResult.Final)
        assertTrue("add_planned_meal must execute", executedTools.contains("add_planned_meal"))
        assertTrue("regenerate_shopping_list must execute", executedTools.contains("regenerate_shopping_list"))

        val meals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals(1, meals.size)

        val activeList = shoppingDao.getActiveShoppingList()
        assertTrue("Shopping list must exist after compound execution", activeList != null)
    }

    @Test
    fun noToolCall_cannotClaimPlannerWasUpdated() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                // Returns pure text without calling any tool
                return AiProviderResponse.FinalResponse("I have added the meals to your planner.")
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

        orchestrator.processUserMessage(conversationId, "add it to the meal plan")

        val meals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals("Text claim without tool execution MUST NOT mutate the database", 0, meals.size)
    }

    @Test
    fun modelTextClaimWithoutWrite_isNotAcceptedAsMutationEvidence() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Done! Added to shopping list.")
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

        orchestrator.processUserMessage(conversationId, "add ingredients to shopping list")

        val activeList = shoppingDao.getActiveShoppingList()
        val itemCount = if (activeList != null) shoppingDao.getShoppingListWithItems(activeList.id)?.items?.size ?: 0 else 0
        assertEquals("Text claim without tool call does NOT write shopping items", 0, itemCount)
    }

    @Test
    fun selectedProposal_isConvertedToTypedToolArguments() = runTest {
        var receivedArgsJson = ""

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t1",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "c1",
                            toolName = "add_planned_meal",
                            argsJson = "{\"title\":\"Grilled Cheese & Tomato Soup\",\"date\":\"2026-10-01\",\"mealType\":\"LUNCH\",\"servings\":2,\"ingredients\":[{\"displayName\":\"Bread\",\"quantity\":4,\"unit\":\"ITEM\"},{\"displayName\":\"Cheddar Cheese\",\"quantity\":100,\"unit\":\"GRAM\"}]}"
                        )
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                receivedArgsJson = request.toolResults.first().payloadJson
                return AiProviderResponse.FinalResponse("Meal added.")
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

        orchestrator.processUserMessage(conversationId, "add option 2 to my planner")

        assertTrue("Tool execution result must contain typed payload JSON", receivedArgsJson.contains("Grilled Cheese"))
        val meals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals(1, meals.size)
        assertEquals("Grilled Cheese & Tomato Soup", meals.first().meal.title)
    }

    @Test
    fun fallbackAfterPlannerWrite_doesNotDuplicatePlannerEntry() = runTest {
        var callCount = 0

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                callCount++
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t_fallback",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "call_idempotent_meal",
                            toolName = "add_planned_meal",
                            argsJson = "{\"title\":\"One-Pot Beef Spaghetti Bolognese\",\"date\":\"2026-09-30\",\"mealType\":\"DINNER\",\"servings\":2,\"ingredients\":[]}"
                        )
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Added meal.")
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

        // Turn 1: Adds meal
        orchestrator.processUserMessage(conversationId, "add option 2 to planner")

        // Turn 2: Fallback model receives same call / retry
        orchestrator.processUserMessage(conversationId, "add option 2 to planner")

        val meals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals("Idempotency must prevent duplicate planned meals", 1, meals.size)
    }

    @Test
    fun retrySameToolCall_doesNotDuplicatePlannedMeal() = runTest {
        val sameCall = AiToolCall(
            toolCallId = "fixed_call_id_123",
            toolName = "add_planned_meal",
            argsJson = "{\"title\":\"Classic Oatmeal\",\"date\":\"2026-09-30\",\"mealType\":\"BREAKFAST\",\"servings\":2,\"ingredients\":[]}"
        )

        val authContext = intentResolver.resolveAuthorizationContext("msg_1", "add oatmeal to planner")

        // Execution 1
        val res1 = aiToolDispatcher.executeToolCall(conversationId, authContext, sameCall)
        assertTrue(res1.success)

        // Execution 2 (Retry with exact same toolCallId)
        val res2 = aiToolDispatcher.executeToolCall(conversationId, authContext, sameCall)
        assertTrue(res2.success)

        val meals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals("Re-executing same tool call MUST NOT duplicate meal record", 1, meals.size)
    }

    @Test
    fun successfulCompoundRequest_persistsPlannerRecords() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t_persist",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "c_persist",
                            toolName = "add_planned_meal",
                            argsJson = "{\"title\":\"Classic Oatmeal\",\"date\":\"2026-09-30\",\"mealType\":\"BREAKFAST\",\"servings\":2,\"ingredients\":[{\"displayName\":\"Oats\",\"quantity\":100,\"unit\":\"GRAM\"}]}"
                        )
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Persisted Oatmeal.")
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

        orchestrator.processUserMessage(conversationId, "add oatmeal to planner")

        val mealsWithIngs = mealDao.getMealWithIngredients("some_id")
        val allPlanned = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")

        assertEquals(1, allPlanned.size)
        assertEquals("Classic Oatmeal", allPlanned.first().meal.title)
        assertEquals(1, allPlanned.first().ingredients.size)
        assertEquals("Oats", allPlanned.first().ingredients.first().displayName)
    }

    @Test
    fun successfulCompoundRequest_persistsShoppingRecords() = runTest {
        // Pre-insert a planned meal
        val todayStr = java.time.LocalDate.now().toString()
        val mealModel = com.example.chatgpt_mealplanner.core.model.Meal(
            id = "meal_compound_1",
            date = todayStr,
            mealType = com.example.chatgpt_mealplanner.core.model.MealType.DINNER,
            title = "One-Pot Bolognese",
            servings = 2,
            status = com.example.chatgpt_mealplanner.core.model.MealStatus.PLANNED,
            batchPortions = 2,
            immediatePortions = 2,
            leftoverPortions = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val ings = listOf(
            com.example.chatgpt_mealplanner.core.model.MealIngredient(
                id = "ing_comp_1",
                mealId = "meal_compound_1",
                displayName = "Spaghetti",
                plannedQuantity = 200.0,
                plannedUnit = QuantityUnit.GRAM
            )
        )
        runBlocking {
            mealDomainService.createPlannedMeal(mealModel, ings)
        }

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t_shop_persist",
                    calls = listOf(
                        AiToolCall("c_shop_persist", "regenerate_shopping_list", "{}")
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Generated shopping items.")
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

        orchestrator.processUserMessage(conversationId, "add ingredients to shopping list")

        val activeList = shoppingDao.getActiveShoppingList()
        assertNotNull(activeList)

        val items = shoppingDao.getShoppingListWithItems(activeList!!.id)?.items ?: emptyList()
        assertTrue("Shopping records must be physically persisted in database", items.isNotEmpty())
        assertEquals("Spaghetti", items.first().item.displayName)
    }

    @Test
    fun conversationSelectionAlone_stillDoesNotMutatePlanner() = runTest {
        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.FinalResponse("Option 2 (Easy Comfort Food) sounds delicious! Let me know when you want to add it to your planner.")
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

        orchestrator.processUserMessage(conversationId, "easy comfort food")

        val meals = mealDao.getUpcomingPlannedMeals("2000-01-01", "2099-12-31")
        assertEquals("Conversational selection alone MUST NOT mutate the planner database", 0, meals.size)
    }

    @Test
    fun explicitUserShoppingRequest_dishwasherTablets_persistsManualShoppingItem() = runTest {
        val invokedTools = mutableListOf<String>()

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t_manual_dish",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "c_manual_dish",
                            toolName = "add_manual_shopping_item",
                            argsJson = "{\"displayName\":\"Dishwasher Tablets\",\"quantity\":1.0,\"unit\":\"PACK\"}"
                        )
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                invokedTools.addAll(request.toolResults.map { it.toolName })
                return AiProviderResponse.FinalResponse("Added Dishwasher Tablets to your shopping list.")
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

        val result = orchestrator.processUserMessage(conversationId, "add dishwasher tablets to my shopping list")

        assertTrue(result is AiOrchestrationResult.Final)
        assertTrue("add_manual_shopping_item must be invoked", invokedTools.contains("add_manual_shopping_item"))

        val activeList = shoppingDao.getActiveShoppingList()
        assertNotNull(activeList)
        val items = shoppingDao.getShoppingListWithItems(activeList!!.id)?.items ?: emptyList()
        assertEquals(1, items.size)
        assertEquals("Dishwasher Tablets", items.first().item.displayName)
    }

    @Test
    fun findCheapestProductRequest_invokesRetailSearchAndAddsToShoppingList() = runTest {
        val executedTools = mutableListOf<String>()
        var round = 0

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                round = 1
                return AiProviderResponse.ToolCalls(
                    providerContinuationToken = "t_retail_1",
                    calls = listOf(
                        AiToolCall(
                            toolCallId = "c_search_prod",
                            toolName = "search_retail_products",
                            argsJson = "{\"query\":\"dishwasher tablets\"}"
                        )
                    )
                )
            }
            override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
                executedTools.addAll(request.toolResults.map { it.toolName })
                if (round == 1) {
                    round = 2
                    return AiProviderResponse.ToolCalls(
                        providerContinuationToken = "t_retail_2",
                        calls = listOf(
                            AiToolCall(
                                toolCallId = "c_add_cheap",
                                toolName = "add_manual_shopping_item",
                                argsJson = "{\"displayName\":\"ASDA All-in-1 Dishwasher Tablets (30 Pack - £3.50)\",\"quantity\":1.0,\"unit\":\"PACK\"}"
                            )
                        )
                    )
                }
                return AiProviderResponse.FinalResponse("Found ASDA All-in-1 Dishwasher Tablets for £3.50 and added them to your shopping list.")
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

        val result = orchestrator.processUserMessage(conversationId, "find the cheapest dishwasher tablets and add them to my list")

        assertTrue(result is AiOrchestrationResult.Final)
        assertTrue("search_retail_products must be executed", executedTools.contains("search_retail_products"))
        assertTrue("add_manual_shopping_item must be executed", executedTools.contains("add_manual_shopping_item"))

        val activeList = shoppingDao.getActiveShoppingList()
        assertNotNull(activeList)
        val items = shoppingDao.getShoppingListWithItems(activeList!!.id)?.items ?: emptyList()
        assertEquals(1, items.size)
        assertTrue(items.first().item.displayName.contains("Dishwasher Tablets"))
    }
}

