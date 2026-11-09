package com.example.chatgpt_mealplanner.core.ai.validation

import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto

/**
 * Finds ingredients that break a stated allergy or diet. This catches clear mistakes by name only.
 * It can't see inside a product, so it never makes a recipe safe: the app still tells people to
 * check labels. An unknown allergy is matched as the word the user typed.
 */
object DietaryRules {
    val ALLERGIES = listOf(
        "Celery", "Crustaceans", "Eggs", "Fish", "Gluten", "Lupin", "Milk",
        "Molluscs", "Mustard", "Peanuts", "Sesame", "Soya", "Sulphites", "Tree nuts"
    )
    val DIETS = listOf("Vegetarian", "Vegan", "Pescatarian", "Gluten-free", "Dairy-free", "Halal", "Kosher")

    private class FoodGroup(words: String, notThis: String = "") {
        private val contains = wordsRegex(words)
        private val exempt = notThis.takeIf { it.isNotBlank() }?.let { Regex("\\b(?:$it)\\b") }

        fun matches(ingredient: String): Boolean =
            contains.containsMatchIn(exempt?.replace(ingredient, " ") ?: ingredient)
    }

    private const val ANIMAL_FREE = "(?:vegan|plant[- ]based|vegetarian)\\s+[a-zé ]+"

    private val milk = FoodGroup(
        "milk|butter|buttermilk|cream|cheese|cheddar|mozzarella|parmesan|parmigiano|feta|halloumi|ricotta|mascarpone|" +
            "yoghurt|yogurt|ghee|whey|creme fraiche|crème fraîche|paneer|custard",
        "$ANIMAL_FREE|(?:dairy|milk)[- ]free\\s+[a-zé ]+|(?:peanut|almond|cashew|nut|cocoa|shea) butter|butter beans?|" +
            "(?:coconut|oat|soya|soy|almond|rice|hazelnut|cashew|nut) (?:(?:single|double|whipping|cooking|sour) )?(?:milk|cream|yoghurt|yogurt)|" +
            "cream of tartar"
    )
    private val eggs = FoodGroup("egg|mayonnaise|mayo|meringue|aioli", "$ANIMAL_FREE|egg[- ]free\\s+[a-zé ]+")
    private val gluten = FoodGroup(
        "wheat|flour|bread|breadcrumb|pasta|spaghetti|penne|fusilli|macaroni|lasagne|tagliatelle|linguine|noodle|couscous|" +
            "bulgur|barley|rye|spelt|semolina|oat|tortilla|wrap|naan|pitta|soy sauce|beer|seitan|pastry",
        "gluten[- ]free\\s+[a-z ]+|(?:rice|corn|gram|almond|coconut|potato|chickpea|buckwheat) (?:flour|noodles?|pasta|tortillas?)"
    )
    private val crustaceans = FoodGroup("prawn|shrimp|crab|lobster|crayfish|langoustine")
    private val molluscs = FoodGroup("mussel|clam|oyster|squid|calamari|octopus|scallop|snail|whelk|cockle")
    private val fish = FoodGroup(
        "fish|salmon|tuna|cod|haddock|mackerel|sardine|anchovy|anchovies|trout|pollock|hake|sea bass|plaice|kipper|worcestershire",
        "$ANIMAL_FREE"
    )
    private val peanuts = FoodGroup("peanut|groundnut|satay")
    private val treeNuts = FoodGroup(
        "almond|hazelnut|walnut|cashew|pecan|pistachio|macadamia|brazil nut|pine nut|nuts|marzipan|praline|frangipane|pesto",
        "nut[- ]free\\s+[a-z ]+"
    )
    private val sesame = FoodGroup("sesame|tahini|hummus|houmous")
    private val soya = FoodGroup("soya|soy|tofu|edamame|miso|tempeh|teriyaki")
    private val mustard = FoodGroup("mustard|dijon")
    private val celery = FoodGroup("celery|celeriac")
    private val lupin = FoodGroup("lupin")
    private val sulphites = FoodGroup("wine|sherry|sulphite|sulfite|dried apricot")
    private val pork = FoodGroup("pork|bacon|ham|gammon|lard|chorizo|salami|pancetta|prosciutto|pepperoni", ANIMAL_FREE)
    private val alcohol = FoodGroup("wine|beer|sherry|brandy|rum|vodka|whisky|cider|ale|port")
    private val meat = FoodGroup(
        "beef|chicken|pork|bacon|ham|lamb|turkey|sausage|mince|duck|gammon|chorizo|salami|pancetta|prosciutto|pepperoni|" +
            "veal|venison|steak|gelatine|lard|suet|meat|meatball",
        "$ANIMAL_FREE|meat[- ]free\\s+[a-z ]+|(?:quorn|soya|soy|lentil) mince"
    )
    private val otherAnimalProducts = FoodGroup("honey", ANIMAL_FREE)

    private val allergyGroups = mapOf(
        "celery" to celery, "crustaceans" to crustaceans, "eggs" to eggs, "fish" to fish, "gluten" to gluten,
        "lupin" to lupin, "milk" to milk, "molluscs" to molluscs, "mustard" to mustard, "peanuts" to peanuts,
        "sesame" to sesame, "soya" to soya, "sulphites" to sulphites, "tree nuts" to treeNuts
    )

    private val dietGroups = mapOf(
        "vegetarian" to listOf(meat, fish, crustaceans, molluscs),
        "vegan" to listOf(meat, fish, crustaceans, molluscs, milk, eggs, otherAnimalProducts),
        "pescatarian" to listOf(meat),
        "gluten-free" to listOf(gluten),
        "dairy-free" to listOf(milk),
        // These two only catch the plainest conflicts. Claude follows the rest from the prompt.
        "halal" to listOf(pork, alcohol),
        "kosher" to listOf(pork, crustaceans, molluscs)
    )

    /**
     * Products that often hide an allergen without naming it. They aren't refused, because a safe
     * brand may exist, but the ingredient has to tell the household to check the label.
     */
    private val oftenHidden = mapOf(
        "fish" to Regex("""\b(?:thai|green|red|yellow|massaman|panang|jungle) curry paste\b|\bcaesar\b|\bnam pla\b|\bxo sauce\b|\bkimchi\b"""),
        "crustaceans" to Regex("""\b(?:thai|green|red|yellow|massaman|panang|jungle) curry paste\b|\bxo sauce\b|\bkimchi\b|\blaksa paste\b"""),
        "molluscs" to Regex("""\boyster sauce\b"""),
        "peanuts" to Regex("""\b(?:massaman|panang) curry paste\b"""),
        "gluten" to Regex("""\b(?:stock cubes?|gravy granules|curry paste)\b"""),
        "milk" to Regex("""\b(?:pesto|gravy granules)\b""")
    )

    private val labelNote = Regex("""\(?\s*check (?:the )?labels?[^)]*\)?""")

    /** The allergies this product often hides, unless its name already says to check the label. */
    fun labelChecksNeeded(ingredientName: String, preferences: UserPreferencesSummaryDto): List<String> {
        val ingredient = ingredientName.lowercase()
        if (labelNote.containsMatchIn(ingredient)) return emptyList()
        return preferences.allergies.filter { allergy -> oftenHidden[allergy.lowercase()]?.containsMatchIn(ingredient) == true }
    }

    fun isMeatOrFish(ingredientName: String): Boolean =
        labelNote.replace(ingredientName.lowercase(), " ").let { meat.matches(it) || fish.matches(it) }

    /** A swap that talks about labels is advice on choosing a safe product, not an ingredient. */
    fun isLabelAdvice(text: String): Boolean = Regex("""\blabels?\b""").containsMatchIn(text.lowercase())

    /** Each allergy or diet this ingredient breaks, written for Claude and the recipe card. */
    fun conflicts(ingredientName: String, preferences: UserPreferencesSummaryDto): List<String> {
        // "Green curry paste (check the label for fish)" names fish to warn about it, not as an ingredient.
        val ingredient = labelNote.replace(ingredientName.lowercase(), " ")
        val allergies = preferences.allergies.filter { allergy ->
            allergyGroups[allergy.lowercase()]?.matches(ingredient) ?: mentions(ingredient, allergy)
        }.map { "'$ingredientName' conflicts with the $it allergy." }
        val diets = preferences.diets.filter { diet ->
            dietGroups[diet.lowercase()].orEmpty().any { it.matches(ingredient) }
        }.map { "'$ingredientName' conflicts with the $it diet." }
        return allergies + diets
    }

    fun isDisliked(ingredientName: String, preferences: UserPreferencesSummaryDto): Boolean =
        preferences.dislikes.any { mentions(ingredientName.lowercase(), it) }

    private fun mentions(ingredient: String, term: String): Boolean =
        term.isNotBlank() && wordsRegex(Regex.escape(term.trim().lowercase())).containsMatchIn(ingredient)

    private fun wordsRegex(words: String) = Regex("\\b(?:$words)(?:s|es)?\\b")
}
