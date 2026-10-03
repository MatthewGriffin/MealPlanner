package com.example.chatgpt_mealplanner.core.ai.pending

import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.database.entity.PendingAiMutationEntity
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class PendingMutationState {
    AWAITING_CONFIRMATION,
    AWAITING_CLARIFICATION
}

sealed interface PendingAiMutation {
    val createdAt: Long
    val state: PendingMutationState

    data class AddPlannedMeal(
        val title: String,
        val date: String? = null,
        val mealType: MealType? = null,
        val servings: Int = 2,
        val ingredients: List<MealIngredient> = emptyList(),
        val instructions: String? = null,
        val sourceUserMessageId: String = "",
        override val state: PendingMutationState = PendingMutationState.AWAITING_CONFIRMATION,
        override val createdAt: Long = System.currentTimeMillis()
    ) : PendingAiMutation

    data class DeleteInventoryItem(
        val itemId: String,
        val targetName: String,
        override val state: PendingMutationState = PendingMutationState.AWAITING_CONFIRMATION,
        override val createdAt: Long = System.currentTimeMillis()
    ) : PendingAiMutation

    data class DeleteAllInventory(
        val totalItemCount: Int,
        override val state: PendingMutationState = PendingMutationState.AWAITING_CONFIRMATION,
        override val createdAt: Long = System.currentTimeMillis()
    ) : PendingAiMutation

    data class PrepareMarkMealCooked(
        val mealId: String,
        val mealTitle: String,
        override val state: PendingMutationState = PendingMutationState.AWAITING_CONFIRMATION,
        override val createdAt: Long = System.currentTimeMillis()
    ) : PendingAiMutation
}

@Singleton
class PendingMutationStore @Inject constructor(
    private val chatDao: ChatDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getPendingMutation(conversationId: String): PendingAiMutation? {
        if (conversationId.isBlank()) return null
        val entity = chatDao.getPendingAiMutation(conversationId) ?: return null
        if (System.currentTimeMillis() - entity.createdAt > 10 * 60 * 1000L) {
            chatDao.deletePendingAiMutation(conversationId)
            return null
        }

        val state = runCatching { PendingMutationState.valueOf(entity.state) }.getOrDefault(PendingMutationState.AWAITING_CONFIRMATION)

        return when (entity.mutationType) {
            "DELETE_INVENTORY_ITEM" -> PendingAiMutation.DeleteInventoryItem(
                itemId = entity.instructions ?: "",
                targetName = entity.title,
                state = state,
                createdAt = entity.createdAt
            )
            "DELETE_ALL_INVENTORY" -> PendingAiMutation.DeleteAllInventory(
                totalItemCount = entity.servings,
                state = state,
                createdAt = entity.createdAt
            )
            "PREPARE_MARK_MEAL_COOKED" -> PendingAiMutation.PrepareMarkMealCooked(
                mealId = entity.instructions ?: "",
                mealTitle = entity.title,
                state = state,
                createdAt = entity.createdAt
            )
            else -> {
                val ings = runCatching {
                    json.decodeFromString<List<MealIngredient>>(entity.ingredientsJson)
                }.getOrDefault(emptyList())

                PendingAiMutation.AddPlannedMeal(
                    title = entity.title,
                    date = entity.date,
                    mealType = entity.mealType?.let { runCatching { MealType.valueOf(it) }.getOrNull() },
                    servings = entity.servings,
                    ingredients = ings,
                    instructions = entity.instructions,
                    sourceUserMessageId = entity.sourceUserMessageId,
                    state = state,
                    createdAt = entity.createdAt
                )
            }
        }
    }

    suspend fun setPendingMutation(conversationId: String, mutation: PendingAiMutation, reason: String = "SET") {
        if (conversationId.isBlank()) return
        val title = when (mutation) {
            is PendingAiMutation.AddPlannedMeal -> mutation.title
            is PendingAiMutation.DeleteInventoryItem -> mutation.targetName
            is PendingAiMutation.DeleteAllInventory -> "ALL_INVENTORY"
            is PendingAiMutation.PrepareMarkMealCooked -> mutation.mealTitle
        }
        val stateStr = when (mutation) {
            is PendingAiMutation.AddPlannedMeal -> mutation.state.name
            is PendingAiMutation.DeleteInventoryItem -> mutation.state.name
            is PendingAiMutation.DeleteAllInventory -> mutation.state.name
            is PendingAiMutation.PrepareMarkMealCooked -> mutation.state.name
        }
        println("[AI_MEALPLANNER] [PENDING_MUTATION_LIFECYCLE] action=SAVE title=\"$title\" state=$stateStr reason=\"$reason\"")
        val entity = when (mutation) {
            is PendingAiMutation.AddPlannedMeal -> PendingAiMutationEntity(
                conversationId = conversationId,
                mutationType = "ADD_PLANNED_MEAL",
                state = mutation.state.name,
                title = mutation.title,
                date = mutation.date,
                mealType = mutation.mealType?.name,
                servings = mutation.servings,
                ingredientsJson = json.encodeToString(mutation.ingredients),
                instructions = mutation.instructions,
                sourceUserMessageId = mutation.sourceUserMessageId,
                createdAt = mutation.createdAt
            )
            is PendingAiMutation.DeleteInventoryItem -> PendingAiMutationEntity(
                conversationId = conversationId,
                mutationType = "DELETE_INVENTORY_ITEM",
                state = mutation.state.name,
                title = mutation.targetName,
                date = null,
                mealType = null,
                servings = 0,
                ingredientsJson = "[]",
                instructions = mutation.itemId,
                sourceUserMessageId = "",
                createdAt = mutation.createdAt
            )
            is PendingAiMutation.DeleteAllInventory -> PendingAiMutationEntity(
                conversationId = conversationId,
                mutationType = "DELETE_ALL_INVENTORY",
                state = mutation.state.name,
                title = "ALL_INVENTORY",
                date = null,
                mealType = null,
                servings = mutation.totalItemCount,
                ingredientsJson = "[]",
                instructions = null,
                sourceUserMessageId = "",
                createdAt = mutation.createdAt
            )
            is PendingAiMutation.PrepareMarkMealCooked -> PendingAiMutationEntity(
                conversationId = conversationId,
                mutationType = "PREPARE_MARK_MEAL_COOKED",
                state = mutation.state.name,
                title = mutation.mealTitle,
                date = null,
                mealType = null,
                servings = 0,
                ingredientsJson = "[]",
                instructions = mutation.mealId,
                sourceUserMessageId = "",
                createdAt = mutation.createdAt
            )
        }
        chatDao.insertOrUpdatePendingAiMutation(entity)
    }

    suspend fun clearPendingMutation(conversationId: String, reason: String = "CLEAR") {
        if (conversationId.isBlank()) return
        val existing = getPendingMutation(conversationId)
        val title = when (existing) {
            is PendingAiMutation.AddPlannedMeal -> existing.title
            is PendingAiMutation.DeleteInventoryItem -> existing.targetName
            is PendingAiMutation.DeleteAllInventory -> "ALL_INVENTORY"
            is PendingAiMutation.PrepareMarkMealCooked -> existing.mealTitle
            null -> "NONE"
        }
        println("[AI_MEALPLANNER] [PENDING_MUTATION_LIFECYCLE] action=CLEAR title=\"$title\" reason=\"$reason\"")
        chatDao.deletePendingAiMutation(conversationId)
    }
}
