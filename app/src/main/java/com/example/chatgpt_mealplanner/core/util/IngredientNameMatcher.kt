package com.example.chatgpt_mealplanner.core.util

/**
 * Decides whether a recipe ingredient name and a stored item name are the same ingredient.
 * Preparation/form words ("dried", "fresh", "chopped", "ground"...) and plurals are ignored, so
 * "dried oregano" matches "oregano". Other words are kept, so "chicken stock" never matches "chicken".
 */
object IngredientNameMatcher {
    private val DESCRIPTORS = setOf(
        "dried", "dry", "fresh", "freshly", "chopped", "diced", "sliced", "minced", "grated", "crushed",
        "ground", "whole", "large", "small", "medium", "ripe", "raw", "finely", "roughly", "organic",
        "a", "of", "the", "extra", "good", "quality", "optional", "powdered", "shredded", "peeled"
    )

    fun core(name: String): String =
        name.lowercase()
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.isNotEmpty() && it !in DESCRIPTORS }
            .map(::singular)
            .sorted()
            .joinToString(" ")

    private fun singular(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("oes") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    fun matches(a: String, b: String): Boolean {
        val ca = core(a)
        return ca.isNotEmpty() && ca == core(b)
    }
}
