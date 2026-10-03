package com.example.chatgpt_mealplanner.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_tool_execution_logs",
    foreignKeys = [
        ForeignKey(
            entity = ChatConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "toolCallId"], unique = true)
    ]
)
data class AiToolExecutionLogEntity(
    @PrimaryKey
    val id: String, // Composite unique key: "${conversationId}_${toolCallId}"
    val conversationId: String,
    val toolName: String,
    val toolCallId: String,
    val executionClass: String,
    val executionStatus: String, // STARTED, SUCCEEDED, FAILED
    val success: Boolean,
    val resultPayloadJson: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val startedAt: Long,
    val completedAt: Long? = null
)
