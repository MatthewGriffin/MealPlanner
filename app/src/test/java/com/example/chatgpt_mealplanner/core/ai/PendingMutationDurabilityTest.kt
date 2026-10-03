package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation
import com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore
import com.example.chatgpt_mealplanner.core.database.entity.PendingAiMutationEntity
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.service.FakeChatDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PendingMutationDurabilityTest {

    private lateinit var chatDao: FakeChatDao
    private lateinit var pendingMutationStore: PendingMutationStore

    @Before
    fun setup() {
        chatDao = FakeChatDao()
        pendingMutationStore = PendingMutationStore(chatDao)
    }

    @Test
    fun instructions_surviveDurablePendingSnapshot_withoutActiveProposal() = runTest {
        val conversationId = "conv_dur_1"
        val instructionsText = "Preheat oven to 200°C.\nRoast for 25 minutes.\nRest for 5 minutes."
        val mutation = PendingAiMutation.AddPlannedMeal(
            title = "Roast Chicken",
            date = "2026-10-01",
            mealType = MealType.DINNER,
            servings = 4,
            ingredients = listOf(
                MealIngredient("ing_1", "", null, "Chicken", 1.5, QuantityUnit.KILOGRAM)
            ),
            instructions = instructionsText,
            sourceUserMessageId = "msg_1"
        )

        // 1. Persist pending mutation
        pendingMutationStore.setPendingMutation(conversationId, mutation)

        // 2. Discard all in-memory active proposal state (simulate process recreation where activeProposal store is empty)
        // 3. Reload pending mutation from durable storage
        val restored = pendingMutationStore.getPendingMutation(conversationId) as? PendingAiMutation.AddPlannedMeal

        // 4. Assert instructions survived exact order and content
        assertNotNull(restored)
        assertEquals(instructionsText, restored?.instructions)
    }

    @Test
    fun legacyNullInstructionPendingMutation_remainsValid() = runTest {
        val conversationId = "conv_legacy_1"
        // Insert legacy entity directly with null instructions
        val legacyEntity = PendingAiMutationEntity(
            conversationId = conversationId,
            mutationType = "ADD_PLANNED_MEAL",
            state = "AWAITING_CONFIRMATION",
            title = "Legacy Soup",
            date = "2026-10-01",
            mealType = "LUNCH",
            servings = 2,
            ingredientsJson = "[]",
            instructions = null,
            sourceUserMessageId = "msg_leg",
            createdAt = System.currentTimeMillis()
        )
        chatDao.insertOrUpdatePendingAiMutation(legacyEntity)

        val restored = pendingMutationStore.getPendingMutation(conversationId) as? PendingAiMutation.AddPlannedMeal
        assertNotNull(restored)
        assertEquals("Legacy Soup", restored?.title)
        assertNull(restored?.instructions)
    }
}
