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
        "a", "of", "the", "extra", "good", "quality", "optional", "powdered", "shredded", "peeled",
        // How a cut is trimmed or prepared, which recipes add and pack names often leave out
        // ("boneless skinless chicken breast, diced" is the "diced chicken breast" that was bought).
        "boneless", "skinless", "skinned", "deboned", "trimmed", "cubed", "halved", "quartered", "thinly", "thickly", "coarsely", "lean",
        // Chicken pieces are sold "skin on, bone in", which a recipe may or may not repeat.
        "skin", "bone", "on", "in",
        // Taste, texture and strength words that describe the same product ("mild cheddar cheese" is the "cheddar cheese" in
        // the pantry; "creamy, fluffy sweet potatoes" are "sweet potatoes"). Words that name a different product stay out of
        // this list: "soft cheese", "strong flour", "golden syrup" and "light mayonnaise" are not the plain thing.
        "mild", "mature", "sharp", "vintage", "creamy", "fluffy", "crispy", "crunchy", "juicy", "tender", "firm", "smooth",
        "tasty", "delicious", "lovely", "nice", "baby", "traditional", "classic", "authentic", "homemade", "free", "range",
        "british", "english", "scottish", "irish", "welsh"
    )

    /** Breads. A "burger bun" or "hot dog roll" is the "bun" or "roll" that was bought: the filling it is for is dropped. */
    private val BREAD_WORDS = setOf("bun", "roll", "bap", "bread", "bagel", "wrap", "loaf", "muffin")
    private val BREAD_USE_WORDS = setOf("burger", "hot", "dog", "hotdog", "sandwich", "sub", "hoagie", "slider")

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
        // "jalapeño" must be "jalapeno": the accent would otherwise split the word.
        val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        var t = " " + plain.lowercase().replace("&", " and ").replace(Regex("\\(.*?\\)"), " ").replace(Regex("[^a-z0-9]+"), " ").trim() + " "
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
            .let { words -> if (words.any { it in BREAD_WORDS }) words.filter { it !in BREAD_USE_WORDS } else words }
            .sorted()
            .joinToString(" ")

    private fun singular(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("oes") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    private val KIT_WORDS = setOf("seasoning", "kit", "mix", "sachet", "packet", "pack", "recipe", "sauce", "flavour", "flavor", "style")

    /**
     * A stored kit or mix ("Chicken risotto") against a recipe's name for it ("Chicken risotto
     * seasoning kit (Schwartz sachet)"). Only for items filed as Kits & Mixes: elsewhere "chicken
     * seasoning" must not match "chicken".
     */
    fun matchesKit(kitName: String, ingredientName: String): Boolean {
        fun kitCore(name: String) = core(name).split(' ').filter { it.isNotEmpty() && it !in KIT_WORDS }.joinToString(" ")
        val kit = kitCore(kitName)
        return kit.isNotEmpty() && kit == kitCore(ingredientName)
    }

    /**
     * A pack sold as "chicken drumsticks and thighs" is both "chicken drumsticks" and "chicken thighs".
     * Only the shared-noun pattern is split (two or more words, "and", one word), so "salt and pepper"
     * or "fish and chips" never stand for one of their parts.
     */
    private fun alternatives(name: String): Set<String> {
        val whole = core(name)
        val text = normalized(name).trim()
        val parts = text.split(" and ")
        if (parts.size != 2) return setOf(whole)
        val first = parts[0].trim().split(' ').filter { it.isNotEmpty() && it !in DESCRIPTORS }
        val last = parts[1].trim().split(' ').filter { it.isNotEmpty() && it !in DESCRIPTORS }
        if (first.size < 2 || last.size != 1) return setOf(whole)
        val noun = first.dropLast(1)
        return setOf(
            whole,
            core(first.joinToString(" ")),
            core((noun + last).joinToString(" "))
        )
    }

    fun matches(a: String, b: String): Boolean {
        val ca = core(a)
        if (ca.isEmpty()) return false
        if (ca == core(b)) return true
        val altsB = alternatives(b)
        return alternatives(a).any { it.isNotEmpty() && it in altsB }
    }
}
