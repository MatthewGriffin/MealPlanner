package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_ai_mutations")
data class PendingAiMutationEntity(
    @PrimaryKey val conversationId: String,
    val mutationType: String = "ADD_PLANNED_MEAL",
    val state: String = "AWAITING_CONFIRMATION",
    val title: String,
    val date: String?,
    val mealType: String?,
    val servings: Int,
    val ingredientsJson: String,
    val instructions: String? = null,
    val sourceUserMessageId: String,
    val createdAt: Long
)

@Entity(tableName = "active_proposals")
data class ActiveProposalEntity(
    @PrimaryKey val conversationId: String,
    val title: String,
    val mealType: String?,
    val suggestedDate: String?,
    val servings: Int,
    val proposalJson: String,
    val createdAt: Long
)
