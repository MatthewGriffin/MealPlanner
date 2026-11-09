package com.example.chatgpt_mealplanner.core.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses a real Room database, because the bug was a foreign key the fakes don't enforce. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ChatRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ChatRepositoryImpl

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = ChatRepositoryImpl(db.chatDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(id: String, role: String = "USER") =
        ChatMessageEntity(id = id, conversationId = "default_conversation", role = role, content = "Hi $id", timestamp = 1L)

    @Test
    fun aMessageAfterTheChatHistoryWasCleared_isStillSaved() = runBlocking {
        repository.saveMessage(message("before"))
        // What Settings > Clear chat history does.
        db.chatDao().observeAllConversations().first().forEach { db.chatDao().deleteConversation(it.id) }

        repository.saveMessage(message("after"))
        repository.saveMessage(message("reply", role = "ASSISTANT"))

        assertNotNull(repository.getConversationById("default_conversation"))
        assertEquals(listOf("after", "reply"), repository.getMessagesForConversation("default_conversation").map { it.id })
    }

    @Test
    fun updatingAConversation_keepsItsMessages() = runBlocking {
        repository.saveMessage(message("kept"))

        repository.createOrUpdateConversation(ChatConversationEntity("default_conversation", "Renamed", 1L, 2L))

        assertEquals("Renamed", repository.getConversationById("default_conversation")?.title)
        assertEquals(listOf("kept"), repository.getMessagesForConversation("default_conversation").map { it.id })
    }
}
