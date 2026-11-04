package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class ChatViewModelWelcomeTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var chatDao: FakeChatDao
    private lateinit var chatRepository: ChatRepositoryImpl
    private lateinit var orchestrator: AiMealPlannerOrchestrator
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        chatDao = FakeChatDao()
        val inventoryDao = FakeInventoryDao()
        val inventoryBarcodeDao = FakeInventoryBarcodeDao()
        val mealDao = FakeMealDao()
        val leftoverDao = FakeLeftoverDao()
        val shoppingDao = FakeShoppingDao()
        val stapleRuleDao = FakeStapleRuleDao()
        val transactionDao = FakeTransactionDao()
        val retailDao = FakeRetailDao()

        val runner = FakeTransactionRunner(
            fakeMealDao = mealDao,
            fakeInventoryDao = inventoryDao,
            fakeTransactionDao = transactionDao,
            fakeLeftoverDao = leftoverDao,
            fakeShoppingDao = shoppingDao,
            fakeStapleRuleDao = stapleRuleDao
        )

        val inventoryDomainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = inventoryBarcodeDao,
            transactionDao = transactionDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )

        val mealDomainService = MealDomainServiceImpl(
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            transactionDao = transactionDao,
            leftoverDao = leftoverDao,
            transactionRunner = runner
        )

        val shoppingDomainService = ShoppingDomainServiceImpl(
            shoppingDao = shoppingDao,
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )


        val pendingMutationStore = com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore(chatDao)
        val activeProposalStore = com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore(chatDao)

        val toolDispatcher = AiToolDispatcher(
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

        val mockProvider = object : MealPlannerAiProvider {
            override suspend fun testConnection() = com.example.chatgpt_mealplanner.contract.AiProviderResponse.FinalResponse("OK")
            override suspend fun send(request: com.example.chatgpt_mealplanner.contract.AiProviderRequest) = com.example.chatgpt_mealplanner.contract.AiProviderResponse.FinalResponse("Hello!")
            override suspend fun continueWithToolResults(request: com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest) = com.example.chatgpt_mealplanner.contract.AiProviderResponse.FinalResponse("Continued")
        }

        chatRepository = ChatRepositoryImpl(chatDao)

        orchestrator = AiMealPlannerOrchestrator(
            provider = mockProvider,
            contextBuilder = AiContextBuilder(inventoryDao, mealDao, leftoverDao, shoppingDao, activeProposalStore, pendingMutationStore),
            toolDispatcher = toolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = UserMutationIntentResolver(),
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        viewModel = ChatViewModel(orchestrator, chatRepository, mealDomainService)
    }

    @Test
    fun init_createsDefaultConversationWithWelcomeMessage() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        val conversations = chatDao.observeAllConversations().first()
        assertEquals(1, conversations.size)

        val conv = conversations.first()
        assertEquals("Pantry Assistant", conv.title)
    }
}
