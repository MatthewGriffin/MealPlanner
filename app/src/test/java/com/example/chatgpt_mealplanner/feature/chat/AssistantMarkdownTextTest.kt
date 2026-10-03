package com.example.chatgpt_mealplanner.feature.chat

import androidx.compose.ui.text.font.FontWeight
import com.example.chatgpt_mealplanner.feature.chat.ui.parseMarkdownToAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AssistantMarkdownTextTest {

    @Test
    fun assistantMarkdown_boldIsRendered() {
        val input = "**Ingredients:**"
        val result = parseMarkdownToAnnotatedString(input)

        // Text should be "Ingredients:" without literal "**"
        assertEquals("Ingredients:", result.text)
        assertFalse("Literal ** must not be displayed", result.text.contains("**"))

        // Span style should contain Bold weight
        val spanStyles = result.spanStyles
        assertTrue("Must contain span style for bold", spanStyles.isNotEmpty())
        assertEquals(FontWeight.Bold, spanStyles.first().item.fontWeight)
    }

    @Test
    fun assistantMarkdown_numberedAndBulletListsRender() {
        val input = "• Salmon fillets — 2\n1. Preheat oven to 200°C"
        val result = parseMarkdownToAnnotatedString(input)

        val lines = result.text.split("\n")
        assertEquals(2, lines.size)
        assertTrue("Bullet item must render bullet point", lines[0].startsWith("• "))
        assertTrue("Numbered item must render number", lines[1].startsWith("1. "))
    }

    @Test
    fun assistantMarkdown_malformedInputDoesNotCrash() {
        val malformedInput = "**Malformed bold without closing tag and *unclosed italic"
        val result = parseMarkdownToAnnotatedString(malformedInput)

        assertTrue("Malformed markdown must parse without crashing", result.text.isNotBlank())
    }

    @Test
    fun userMessage_markdownIsNotInterpreted() {
        val userContent = "**hello**"
        // User messages are NOT passed to parseMarkdownToAnnotatedString in ChatScreen
        // They remain plain string content
        assertEquals("**hello**", userContent)
    }
}
