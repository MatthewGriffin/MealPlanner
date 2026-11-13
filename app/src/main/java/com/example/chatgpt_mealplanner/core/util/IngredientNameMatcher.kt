package com.example.chatgpt_mealplanner.core.util

/**
 * Decides whether a recipe ingredient name and a stored item name are the same ingredient.
 * Preparation/form words ("dried", "fresh", "chopped", "ground"...) and plurals are ignored, so
 * "dried oregano" matches "oregano". Other words are kept, so "chicken stock" never matches "chicken".
 */
object IngredientNameMatcher {
    private val DESCRIPTORS = setOf(
        "dried", "dry", "fresh", "freshly", "chopped", "diced", "sliced", "minced", "grated", "crushed", "ground",
        "whole", "large", "small", "medium", "ripe", "raw", "finely", "roughly", "organic",
        "a", "of", "the", "extra", "good", "quality", "optional", "powdered", "shredded", "peeled"
    )

    /** Same ingredient, different names (mostly UK vs US). Phrases are replaced before words are compared. */
    private val SYNONYMS = listOf(
        "cilantro" to "coriander",
        "zucchini" to "courgette",
        "eggplant" to "aubergine",
        "arugula" to "rocket",
        "scallion" to "spring onion",
        "green onion" to "spring onion",
        "bell pepper" to "capsicum",
        "shrimp" to "prawn",
        "garbanzo" to "chickpea",
        "rutabaga" to "swede",
        "beet" to "beetroot",
        "heavy cream" to "double cream",
        "whipping cream" to "double cream",
        "light cream" to "single cream",
        "superfine sugar" to "caster sugar",
        "powdered sugar" to "icing sugar",
        "confectioners sugar" to "icing sugar",
        "all purpose flour" to "plain flour",
        "baking soda" to "bicarbonate of soda",
        "cornstarch" to "cornflour",
        "ground beef" to "beef mince",
        "ground pork" to "pork mince",
        "ground lamb" to "lamb mince",
        "ground turkey" to "turkey mince",
        "ground chicken" to "chicken mince",
        "fries" to "chips"
    )

    private fun normalized(name: String): String {
        var t = " " + name.lowercase().replace(Regex("\\(.*?\\)"), " ").replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        for ((from, to) in SYNONYMS) {
            t = t.replace(" $from ", " $to ").replace(" ${from}s ", " $to ")
        }
        return t
    }

    fun core(name: String): String =
        normalized(name)
            .trim()
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
