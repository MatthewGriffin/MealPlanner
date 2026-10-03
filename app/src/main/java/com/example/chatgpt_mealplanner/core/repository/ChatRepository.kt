package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeAllConversations(): Flow<List<ChatConversationEntity>>
    suspend fun getConversationById(id: String): ChatConversationEntity?
    suspend fun createOrUpdateConversation(conversation: ChatConversationEntity)
    suspend fun deleteConversation(id: String)

    fun observeMessagesForConversation(conversationId: String): Flow<List<ChatMessageEntity>>
    suspend fun getMessagesForConversation(conversationId: String): List<ChatMessageEntity>
    suspend fun saveMessage(message: ChatMessageEntity)
}
