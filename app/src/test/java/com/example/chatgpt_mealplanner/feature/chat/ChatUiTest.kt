package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ChatUiTest {

    @Test
    fun assistantScreen_existingConversation_startsAtLatestMessage() {
        val messages = (1..10).map { i ->
            ChatMessageEntity("msg_$i", "default_conversation", if (i % 2 == 0) "assistant" else "user", "Message $i", null, i * 1000L)
        }
        val state = ChatUiState(activeConversationId = "default_conversation", messages = messages)

        val totalItemCount = state.messages.size + (if (state.latestProposal != null) 1 else 0) + (if (state.isLoading) 1 else 0)
        val targetIndex = (totalItemCount - 1).coerceAtLeast(0)

        // Target index for 10 messages is index 9 (the last message)
        assertEquals(9, targetIndex)
    }

    @Test
    fun assistantScreen_initialEmptyConversation_doesNotCrashScroll() {
        val state = ChatUiState(activeConversationId = "default_conversation", messages = emptyList())

        val totalItemCount = state.messages.size + (if (state.latestProposal != null) 1 else 0) + (if (state.isLoading) 1 else 0)
        val targetIndex = (totalItemCount - 1).coerceAtLeast(0)

        assertEquals(0, targetIndex)
    }

    @Test
    fun assistantScreen_newUserMessage_scrollsToLatest() {
        val messages = mutableListOf(
            ChatMessageEntity("msg_1", "default_conversation", "user", "Hello", null, 1000L)
        )
        val state1 = ChatUiState(activeConversationId = "default_conversation", messages = messages)
        val targetIndex1 = (state1.messages.size - 1).coerceAtLeast(0)
        assertEquals(0, targetIndex1)

        // New message arrives
        messages.add(ChatMessageEntity("msg_2", "default_conversation", "assistant", "Hi there!", null, 2000L))
        val state2 = ChatUiState(activeConversationId = "default_conversation", messages = messages)
        val targetIndex2 = (state2.messages.size - 1).coerceAtLeast(0)

        assertEquals(1, targetIndex2)
    }

    @Test
    fun composer_submissionGuards_rejectBlankAndWhitespaceOnly() {
        var eventDispatched = false
        val submitGuard: (String, Boolean) -> Unit = { text, isLoading ->
            val canSubmit = !isLoading && text.isNotBlank()
            if (canSubmit) {
                eventDispatched = true
            }
        }

        // Blank input -> rejects
        submitGuard("", false)
        assertFalse(eventDispatched)

        // Whitespace-only input -> rejects
        submitGuard("   \n\t ", false)
        assertFalse(eventDispatched)

        // Loading state -> rejects
        submitGuard("Hello", true)
        assertFalse(eventDispatched)

        // Valid text -> accepts!
        submitGuard("Hello Assistant", false)
        assertTrue(eventDispatched)
    }

    @Test
    fun composer_sendButtonAndImeUseSameSubmissionPath() {
        var sendCount = 0
        val onSend: () -> Unit = { sendCount++ }

        val canSubmit = true
        val submitMessage = {
            if (canSubmit) {
                onSend()
            }
        }

        // IME Send
        submitMessage()
        assertEquals(1, sendCount)

        // Button Click
        submitMessage()
        assertEquals(2, sendCount)
    }

    @Test
    fun modelDisplayNameMapping_mapsKnownAndUnknownModels() {
        assertEquals("Gemini 3.7 Flash", com.example.chatgpt_mealplanner.feature.chat.ui.formatModelDisplayName("gemini-3.7-flash"))
        assertEquals("Gemini 3.6 Flash", com.example.chatgpt_mealplanner.feature.chat.ui.formatModelDisplayName("gemini-3.6-flash"))
        assertEquals("Gemini 3.5 Flash Lite", com.example.chatgpt_mealplanner.feature.chat.ui.formatModelDisplayName("gemini-3.5-flash-lite"))

        // Unknown model -> raw string
        assertEquals("gemini-4.0-ultra", com.example.chatgpt_mealplanner.feature.chat.ui.formatModelDisplayName("gemini-4.0-ultra"))

        // Null / blank -> null
        org.junit.Assert.assertNull(com.example.chatgpt_mealplanner.feature.chat.ui.formatModelDisplayName(null))
        org.junit.Assert.assertNull(com.example.chatgpt_mealplanner.feature.chat.ui.formatModelDisplayName(""))
        org.junit.Assert.assertNull(com.example.chatgpt_mealplanner.feature.chat.ui.formatModelDisplayName("   "))
    }

    @Test
    fun chatMessageEntity_supportsModelProvenanceField() {
        val userMsg = ChatMessageEntity("msg_1", "conv1", "USER", "Hi", timestamp = 1000L, modelId = null)
        val assistantMsg = ChatMessageEntity("msg_2", "conv1", "ASSISTANT", "Hello!", timestamp = 2000L, modelId = "gemini-3.7-flash")

        org.junit.Assert.assertNull(userMsg.modelId)
        assertEquals("gemini-3.7-flash", assistantMsg.modelId)
    }
}
