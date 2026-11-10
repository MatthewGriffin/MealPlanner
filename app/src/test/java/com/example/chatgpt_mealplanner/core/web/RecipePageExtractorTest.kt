package com.example.chatgpt_mealplanner.core.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipePageExtractorTest {
    @Test
    fun usesTheRecipeDataWhenThePageHasIt() {
        val html = """<html><head><script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[{"@type":"WebSite","name":"Site"},
            {"@type":"Recipe","name":"Shakshuka","recipeYield":"2","recipeIngredient":["4 eggs","1 tin tomatoes"],
             "recipeInstructions":[{"@type":"HowToStep","text":"Simmer the tomatoes 10 minutes."},{"@type":"HowToStep","text":"Poach the eggs."}]}]}
            </script></head><body><nav>Menu</nav><p>Lots of life story</p></body></html>"""
        val out = RecipePageExtractor.extract(html)
        assertTrue(out.contains("Shakshuka"))
        assertTrue(out.contains("4 eggs"))
        assertTrue(out.contains("Simmer the tomatoes 10 minutes."))
        assertFalse(out.contains("life story"))
    }

    @Test
    fun fallsBackToThePageText() {
        val out = RecipePageExtractor.extract("<html><script>var x=1;</script><body><h1>Soup</h1><p>Boil &amp; stir.</p></body></html>")
        assertTrue(out.contains("Soup"))
        assertTrue(out.contains("Boil & stir."))
        assertFalse(out.contains("var x"))
    }
}
