package com.example.chatgpt_mealplanner.core.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickRepliesParsingTest {

    @Test
    fun theMarkerLineIsRemovedAndItsAnswersReturned() {
        val (text, replies) = ModelReplyText.withoutQuickReplies(
            "Shall I swap the chicken for tofu?\nQUICK_REPLIES: [\"Yes, swap it\", \"No, keep chicken\", \"Use fish instead\"]"
        )
        assertEquals("Shall I swap the chicken for tofu?", text)
        assertEquals(listOf("Yes, swap it", "No, keep chicken", "Use fish instead"), replies)
    }

    @Test
    fun replyWithoutTheMarkerIsUntouched() {
        val (text, replies) = ModelReplyText.withoutQuickReplies("Here you go.")
        assertEquals("Here you go.", text)
        assertEquals(emptyList<String>(), replies)
    }

    @Test
    fun anUnreadableLineIsStillHiddenAndGivesNoButtons() {
        val (text, replies) = ModelReplyText.withoutQuickReplies("Which day?\nQUICK_REPLIES: [oops")
        assertEquals("Which day?", text)
        assertEquals(emptyList<String>(), replies)
    }

    @Test
    fun answersAreTrimmedDeduplicatedCappedAndKeptShort() {
        val long = "x".repeat(60)
        val (_, replies) = ModelReplyText.withoutQuickReplies(
            "Q?\nQUICK_REPLIES: [\" A \", \"A\", \"$long\", \"B\", \"C\", \"D\", \"E\"]"
        )
        assertEquals(listOf("A", "B", "C", "D"), replies)
    }
}
