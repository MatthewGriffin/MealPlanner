package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.chatgpt_mealplanner.core.database.entity.ActiveProposalEntity
import com.example.chatgpt_mealplanner.core.database.entity.AiToolExecutionLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import com.example.chatgpt_mealplanner.core.database.entity.PendingAiMutationEntity
import kotlinx.coroutines.flow.Flow

data class ChatConversationWithMessages(
    @androidx.room.Embedded val conversation: ChatConversationEntity,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "conversationId"
    )
    val messages: List<ChatMessageEntity>
)

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    fun observeAllConversations(): Flow<List<ChatConversationEntity>>

    @Transaction
    @Query("SELECT * FROM chat_conversations ORDER BY createdAt ASC")
    suspend fun getAllConversationsWithMessagesSync(): List<ChatConversationWithMessages>

    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ChatConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateConversation(conversation: ChatConversationEntity)

    @Query("DELETE FROM chat_conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeMessagesForConversation(conversationId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversation(conversationId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteAllMessagesForConversation(conversationId: String)

    @Query("SELECT * FROM ai_tool_execution_logs WHERE conversationId = :conversationId AND toolCallId = :toolCallId ORDER BY executionStatus = 'SUCCEEDED' DESC, startedAt DESC LIMIT 1")
    suspend fun getToolExecutionLog(conversationId: String, toolCallId: String): AiToolExecutionLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateToolExecutionLog(log: AiToolExecutionLogEntity)

    @Query("DELETE FROM ai_tool_execution_logs WHERE startedAt < :timestamp")
    suspend fun deleteLogsOlderThan(timestamp: Long)

    @Query("SELECT * FROM pending_ai_mutations WHERE conversationId = :conversationId")
    suspend fun getPendingAiMutation(conversationId: String): PendingAiMutationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePendingAiMutation(entity: PendingAiMutationEntity)

    @Query("DELETE FROM pending_ai_mutations WHERE conversationId = :conversationId")
    suspend fun deletePendingAiMutation(conversationId: String)

    @Query("SELECT * FROM active_proposals WHERE conversationId = :conversationId")
    suspend fun getActiveProposal(conversationId: String): ActiveProposalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateActiveProposal(entity: ActiveProposalEntity)

    @Query("DELETE FROM active_proposals WHERE conversationId = :conversationId")
    suspend fun deleteActiveProposal(conversationId: String)
}
