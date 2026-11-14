package com.example.chatgpt_mealplanner.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeTextParserTest {
    private val text = """
        Prep 10 min • Cook 25 min • Equipment: Air fryer, Small saucepan with lid
        1. Rinse the rice. (12 min)
        2. Cut the chicken, no time given.
        Swap Lime juice for lemon juice
        Worth adding: Fresh mint: brightens it
        Based on: BBC Good Food: https://example.com/x
    """.trimIndent()

    @Test fun splitsEverySection() {
        val p = RecipeTextParser.parse(text)
        assertEquals(10, p.prepMinutes)
        assertEquals(25, p.cookMinutes)
        assertEquals(listOf("Air fryer", "Small saucepan with lid"), p.equipment)
        assertEquals(2, p.steps.size)
        assertEquals("Rinse the rice.", p.steps[0].text)
        assertEquals(12, p.steps[0].minutes)
        assertNull(p.steps[1].minutes)
        assertEquals(listOf("Swap Lime juice for lemon juice"), p.swaps)
        assertEquals(listOf("Fresh mint: brightens it"), p.worthAdding)
        assertEquals("BBC Good Food: https://example.com/x", p.basedOn)
        assertEquals(emptyList<String>(), p.other)
    }

    @Test fun unstructuredTextIsKept() {
        val p = RecipeTextParser.parse("Just mix it all and bake.")
        assertEquals(listOf("Just mix it all and bake."), p.other)
        assertEquals(0, p.steps.size)
    }
}
