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

    /**
     * Creates the conversation first if it's gone, because a message can't be saved without it.
     * Clearing the chat history in Settings deletes every conversation, and the chat screen only
     * creates its own when it starts, so replies after a clear were refused and the chat stayed blank.
     */
    override suspend fun saveMessage(message: ChatMessageEntity) {
        try {
            if (chatDao.getConversationById(message.conversationId) == null) {
                val now = System.currentTimeMillis()
                chatDao.insertOrUpdateConversation(
                    ChatConversationEntity(id = message.conversationId, title = DEFAULT_TITLE, createdAt = now, updatedAt = now)
                )
            }
            chatDao.insertMessage(message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Callers carry on without the message, so this is the only trace of it.
            runCatching { android.util.Log.e("AI_MEALPLANNER", "[CHAT_SAVE_FAILED] role=${message.role} conversation=${message.conversationId}", e) }
            throw e
        }
    }

    private companion object {
        const val DEFAULT_TITLE = "Pantry Assistant"
    }
}
