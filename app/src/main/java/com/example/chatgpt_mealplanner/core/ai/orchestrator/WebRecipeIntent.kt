package com.example.chatgpt_mealplanner.core.ai.orchestrator

/**
 * Whether the user asked for a recipe from the web, or an authentic or named one. Searching costs
 * about 1p and adds several seconds, so ordinary recipe requests don't search.
 */
object WebRecipeIntent {

    fun asksForWebRecipe(userMessageText: String): Boolean {
        val lower = userMessageText.lowercase()
        if (!recipeWord.containsMatchIn(lower)) return false
        return searchVerb.containsMatchIn(lower) || authentic.containsMatchIn(lower) || named.containsMatchIn(lower)
    }

    private val recipeWord = Regex("""\b(?:recipes?|dish(?:es)?|meals?|dinner|lunch|breakfast|version)\b""")
    private val searchVerb = Regex("""\b(?:look(?:ing)? up|search(?:ing)?|google|online|internet|(?:on|from) the web|find(?: me)?)\b""")
    private val authentic = Regex("""\b(?:real|authentic|traditional|genuine|original|famous)\b""")
    private val named = Regex("""\b[a-z]+'s\s+(?:[a-z-]+\s+){0,4}recipe\b|\bfrom\s+(?:bbc|good food|jamie|nigella|delia|ottolenghi|mob|hairy bikers|mary berry)""")
}
