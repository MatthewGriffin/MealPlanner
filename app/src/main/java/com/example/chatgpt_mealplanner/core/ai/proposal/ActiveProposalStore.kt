package com.example.chatgpt_mealplanner.core.ai.proposal

import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.database.entity.ActiveProposalEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveProposalStore @Inject constructor(
    private val chatDao: ChatDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getActiveProposal(conversationId: String): MealProposal? {
        if (conversationId.isBlank()) return null
        val entity = chatDao.getActiveProposal(conversationId) ?: return null
        return runCatching {
            json.decodeFromString<MealProposal>(entity.proposalJson)
        }.getOrNull()
    }

    suspend fun setActiveProposal(conversationId: String, proposal: MealProposal) {
        if (conversationId.isBlank()) return
        val existing = getActiveProposal(conversationId)
        if (existing != null && existing.ingredients.isNotEmpty() && proposal.ingredients.isEmpty()) {
            println("[AI_MEALPLANNER] [PROPOSAL_STATE_PRECEDENCE] existingSource=\"STRUCTURED_TOOL\" incomingSource=\"MARKDOWN_FALLBACK\" existingIngredientCount=${existing.ingredients.size} incomingIngredientCount=0 action=KEEP_EXISTING")
            return
        }
        val entity = ActiveProposalEntity(
            conversationId = conversationId,
            title = proposal.title,
            mealType = proposal.mealType,
            suggestedDate = proposal.suggestedDate,
            servings = proposal.servings,
            proposalJson = json.encodeToString(proposal),
            createdAt = System.currentTimeMillis()
        )
        chatDao.insertOrUpdateActiveProposal(entity)
    }

    suspend fun clearActiveProposal(conversationId: String) {
        if (conversationId.isBlank()) return
        chatDao.deleteActiveProposal(conversationId)
    }
}
