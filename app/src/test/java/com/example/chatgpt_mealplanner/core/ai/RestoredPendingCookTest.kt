package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation
import com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore
import com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealEntity
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.repository.ChatRepositoryImpl
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * A "mark as cooked" waiting for confirmation is saved to the database, so it survives the app
 * being killed. Cooking deducts inventory, so a restored one must never run on its own.
 */
@RunWith(JUnit4::class)
class RestoredPendingCookTest {

    private val conversationId = "conv_cook"
    private val mealId = "meal_1"

    // These outlive the "process", like the database does.
    private val chatDao = FakeChatDao()
    private val mealDao = FakeMealDao()
    private val inventoryDao = FakeInventoryDao()
    private val leftoverDao = FakeLeftoverDao()
    private val shoppingDao = FakeShoppingDao()
    private val stapleRuleDao = FakeStapleRuleDao()
    private val transactionDao = FakeTransactionDao()

    /** The AI always tries to mark the meal cooked, so only the confirmation checks can stop it. */
    private class CookingProvider(private val mealId: String) : MealPlannerAiProvider {
        override suspend fun testConnection(): AiProviderResponse = AiProviderResponse.FinalResponse("OK")
        override suspend fun send(request: AiProviderRequest): AiProviderResponse = AiProviderResponse.ToolCalls(
            providerContinuationToken = "token",
            calls = listOf(AiToolCall("call_cook", "prepare_mark_meal_cooked", "{\"mealId\":\"$mealId\"}"))
        )
        override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse =
            AiProviderResponse.FinalResponse("Done.")
    }

    /** Builds everything that lives in memory from scratch, as a restarted app would. */
    private fun restartApp(): AiMealPlannerOrchestrator {
        val pendingStore = PendingMutationStore(chatDao)
        val proposalStore = ActiveProposalStore(chatDao)
        val runner = FakeTransactionRunner(
            fakeMealDao = mealDao,
            fakeInventoryDao = inventoryDao,
            fakeTransactionDao = transactionDao,
            fakeLeftoverDao = leftoverDao,
            fakeShoppingDao = shoppingDao,
            fakeStapleRuleDao = stapleRuleDao
        )
        val dispatcher = AiToolDispatcher(
            inventoryDao = inventoryDao,
            mealDao = mealDao,
            leftoverDao = leftoverDao,
            shoppingDao = shoppingDao,
            inventoryDomainService = InventoryDomainServiceImpl(
                inventoryDao = inventoryDao,
                inventoryBarcodeDao = FakeInventoryBarcodeDao(),
                transactionDao = transactionDao,
                stapleRuleDao = stapleRuleDao,
                transactionRunner = runner
            ),
            mealDomainService = MealDomainServiceImpl(
                mealDao = mealDao,
                inventoryDao = inventoryDao,
                transactionDao = transactionDao,
                leftoverDao = leftoverDao,
                transactionRunner = runner
            ),
            shoppingDomainService = ShoppingDomainServiceImpl(
                shoppingDao = shoppingDao,
                mealDao = mealDao,
                inventoryDao = inventoryDao,
                stapleRuleDao = stapleRuleDao,
                transactionRunner = runner
            ),
            chatDao = chatDao,
            pendingMutationStore = pendingStore,
            activeProposalStore = proposalStore
        )
        return AiMealPlannerOrchestrator(
            provider = CookingProvider(mealId),
            contextBuilder = AiContextBuilder(
                inventoryDao = inventoryDao,
                mealDao = mealDao,
                leftoverDao = leftoverDao,
                shoppingDao = shoppingDao,
                activeProposalStore = proposalStore,
                pendingMutationStore = pendingStore
            ),
            toolDispatcher = dispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = UserMutationIntentResolver(),
            chatRepository = ChatRepositoryImpl(chatDao),
            pendingMutationStore = pendingStore,
            activeProposalStore = proposalStore
        )
    }

    private suspend fun stagePendingCook(createdAt: Long = System.currentTimeMillis()) {
        chatDao.insertOrUpdateConversation(ChatConversationEntity(conversationId, "Chat", 1L, 1L))
        mealDao.insertMeal(
            MealEntity(
                id = mealId,
                date = "2026-11-04",
                mealType = MealType.DINNER,
                title = "Pasta",
                servings = 2,
                status = MealStatus.PLANNED,
                batchPortions = 2,
                immediatePortions = 2,
                leftoverPortions = 0,
                cookInTwoBatches = false,
                tags = emptySet(),
                instructions = null,
                notes = null,
                cookedAt = null,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        PendingMutationStore(chatDao).setPendingMutation(
            conversationId,
            PendingAiMutation.PrepareMarkMealCooked(mealId, "Pasta", createdAt = createdAt)
        )
    }

    private suspend fun mealStatus() = mealDao.getMealWithIngredients(mealId)!!.meal.status

    private suspend fun pendingAfterRestart() = PendingMutationStore(chatDao).getPendingMutation(conversationId)

    @Test
    fun restart_keepsTheRequest_withoutCooking() = runTest {
        stagePendingCook()

        restartApp()

        assertTrue(pendingAfterRestart() is PendingAiMutation.PrepareMarkMealCooked)
        assertEquals(MealStatus.PLANNED, mealStatus())
    }

    @Test
    fun afterRestart_anUnrelatedMessageDoesNotCook_evenIfTheAiAsksTo() = runTest {
        stagePendingCook()

        restartApp().processUserMessage(conversationId, "what's a good side dish for pasta?")

        assertEquals(MealStatus.PLANNED, mealStatus())
    }

    @Test
    fun afterRestart_decliningDoesNotCook_andClearsTheRequest() = runTest {
        stagePendingCook()

        restartApp().processUserMessage(conversationId, "no")

        assertEquals(MealStatus.PLANNED, mealStatus())
        assertNull(pendingAfterRestart())
    }

    @Test
    fun afterRestart_confirmingCooksOnce() = runTest {
        stagePendingCook()

        val result = restartApp().processUserMessage(conversationId, "yes")

        assertTrue("Confirmation should succeed: $result", result !is AiOrchestrationResult.Error)
        assertEquals(MealStatus.COOKED, mealStatus())
        assertNull(pendingAfterRestart())
        assertNotNull(mealDao.getMealWithIngredients(mealId)!!.meal.cookedAt)
    }

    @Test
    fun anExpiredRequest_isDropped_soALateYesDoesNotCook() = runTest {
        val expiredAt = System.currentTimeMillis() - 11 * 60 * 1000L
        stagePendingCook(createdAt = expiredAt)

        restartApp().processUserMessage(conversationId, "yes")

        assertEquals(MealStatus.PLANNED, mealStatus())
        // The fake AI asks again, which may stage a new request. It still needs its own confirmation.
        assertNotEquals(expiredAt, pendingAfterRestart()?.createdAt)
    }
}
