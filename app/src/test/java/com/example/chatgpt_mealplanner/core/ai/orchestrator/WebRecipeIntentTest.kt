package com.example.chatgpt_mealplanner.core.ai.orchestrator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRecipeIntentTest {

    @Test
    fun askingForARecipeFromTheWeb_searches() {
        listOf(
            "find me a real recipe for chicken shawarma traybake",
            "look up a recipe for pad thai",
            "search online for a good lasagne recipe",
            "give me an authentic Thai green curry recipe",
            "a traditional Lancashire hotpot for dinner",
            "Jamie Oliver's chicken pie recipe",
            "something from BBC Good Food for dinner"
        ).forEach { assertTrue(it, WebRecipeIntent.asksForWebRecipe(it)) }
    }

    @Test
    fun ordinaryRequests_dontSearch() {
        listOf(
            "suggest a recipe for dinner",
            "give me a new dinner idea",
            "what can I cook tonight?",
            "find the milk in my inventory",
            "make it spicier",
            "how long does cooked rice keep?"
        ).forEach { assertFalse(it, WebRecipeIntent.asksForWebRecipe(it)) }
    }
}
