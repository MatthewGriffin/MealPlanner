package com.example.chatgpt_mealplanner.core.ai.eval

import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
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

data class EvalTurnResult(
    val prompt: String,
    val isSuccess: Boolean,
    val outputText: String,
    val errorCode: String? = null
)

class ModelEvaluationHarness(
    private val aiProvider: MealPlannerAiProvider
) {
    suspend fun runEvaluationTurn(conversationId: String, userPrompt: String): EvalTurnResult {
        val inventoryDao = FakeInventoryDao()
        val inventoryBarcodeDao = FakeInventoryBarcodeDao()
        val mealDao = FakeMealDao()
        val leftoverDao = FakeLeftoverDao()
        val shoppingDao = FakeShoppingDao()
        val stapleRuleDao = FakeStapleRuleDao()
        val transactionDao = FakeTransactionDao()
        val retailDao = FakeRetailDao()
        val chatDao = FakeChatDao()

        val runner = FakeTransactionRunner(
            fakeMealDao = mealDao,
            fakeInventoryDao = inventoryDao,
            fakeTransactionDao = transactionDao,
            fakeLeftoverDao = leftoverDao,
            fakeShoppingDao = shoppingDao,
            fakeStapleRuleDao = stapleRuleDao
        )

        val chatRepository = ChatRepositoryImpl(chatDao)

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

        val retailRepo = RetailRepositoryImpl(retailDao)
        val retailShoppingDomainService = RetailShoppingDomainServiceImpl(
            shoppingDomainService = shoppingDomainService,
            shoppingDao = shoppingDao,
            retailRepository = retailRepo,
            retailDao = retailDao
        )

        val pendingMutationStore = com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore(chatDao)
        val activeProposalStore = com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore(chatDao)

        val aiToolDispatcher = AiToolDispatcher(
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

        val intentResolver = UserMutationIntentResolver()
        val contextBuilder = AiContextBuilder(
            inventoryDao = inventoryDao,
            mealDao = mealDao,
            leftoverDao = leftoverDao,
            shoppingDao = shoppingDao,
            activeProposalStore = activeProposalStore,
            pendingMutationStore = pendingMutationStore
        )

        val orchestrator = AiMealPlannerOrchestrator(
            provider = aiProvider,
            contextBuilder = contextBuilder,
            toolDispatcher = aiToolDispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = intentResolver,
            chatRepository = chatRepository,
            pendingMutationStore = pendingMutationStore,
            activeProposalStore = activeProposalStore
        )

        return when (val result = orchestrator.processUserMessage(conversationId, userPrompt)) {
            is AiOrchestrationResult.Final -> EvalTurnResult(
                prompt = userPrompt,
                isSuccess = true,
                outputText = result.assistantText
            )
            is AiOrchestrationResult.Error -> EvalTurnResult(
                prompt = userPrompt,
                isSuccess = false,
                outputText = result.userFacingMessage,
                errorCode = result.code
            )
        }
    }
}
