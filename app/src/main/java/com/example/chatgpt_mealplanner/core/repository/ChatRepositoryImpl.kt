package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao
) : ChatRepository {

    override fun observeAllConversations(): Flow<List<ChatConversationEntity>> =
        chatDao.observeAllConversations()

    override suspend fun getConversationById(id: String): ChatConversationEntity? =
        chatDao.getConversationById(id)

    override suspend fun createOrUpdateConversation(conversation: ChatConversationEntity) =
        chatDao.insertOrUpdateConversation(conversation)

    override suspend fun deleteConversation(id: String) =
        chatDao.deleteConversation(id)

    override fun observeMessagesForConversation(conversationId: String): Flow<List<ChatMessageEntity>> =
        chatDao.observeMessagesForConversation(conversationId)

    override suspend fun getMessagesForConversation(conversationId: String): List<ChatMessageEntity> =
        chatDao.getMessagesForConversation(conversationId)

    override suspend fun saveMessage(message: ChatMessageEntity) =
        chatDao.insertMessage(message)
}
