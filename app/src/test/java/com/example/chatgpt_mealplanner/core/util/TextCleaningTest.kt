package com.example.chatgpt_mealplanner.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextCleaningTest {

    @Test
    fun emojiAreRemovedAndTheSpacingTidied() {
        assertEquals("Broccoli and Stilton soup", "Broccoli 🥦 and Stilton soup".withoutPictographs())
        assertEquals("Chicken tikka", "🍗Chicken tikka🔥".withoutPictographs())
        assertEquals("Heart", "❤️ Heart".withoutPictographs())
        assertEquals("Rice", "Rice ™".withoutPictographs())
    }

    @Test
    fun lettersInAnyLanguageDigitsAndPunctuationStay() {
        assertEquals("Crème fraîche 300g (Président) & co.", "Crème fraîche 300g (Président) & co.".withoutPictographs())
        assertEquals("Българско сирене 500 г", "Българско сирене 500 г".withoutPictographs())
        assertEquals("Mac 'n' cheese – 2 x 100g", "Mac 'n' cheese – 2 x 100g".withoutPictographs())
    }

    @Test
    fun aNameThatWasOnlyEmojiBecomesNull() {
        assertNull("🥦🥕".withoutPictographsOrNull())
        assertNull((null as String?).withoutPictographsOrNull())
        assertEquals("Soup", " Soup 🍲 ".withoutPictographsOrNull())
    }
}
