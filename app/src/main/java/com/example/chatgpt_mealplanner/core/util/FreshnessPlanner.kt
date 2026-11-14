package com.example.chatgpt_mealplanner.core.util

import java.time.LocalDate

/**
 * Estimates how long fresh ingredients keep after being bought, so a shopping list can be split
 * into trips: fresh chicken for a meal at the end of the week is bought later in the week.
 * Estimates are deliberately cautious (fridge, bought fresh) and by name only.
 */
object FreshnessPlanner {

    /** Days a fresh item stays good after purchase, or null when it keeps long enough not to matter. */
    fun shelfLifeDays(name: String): Int? {
        val n = name.lowercase()
        // Frozen, tinned, dried and jarred versions keep for ages.
        if (LONG_LIFE_WORDS.any { n.contains(it) }) return null
        return RULES.firstOrNull { (words, _) -> words.any { n.contains(it) } }?.second
    }

    /** One shop's worth of a perishable: meals whose dates fall inside the same keeping window. */
    fun <T> batchByShelfLife(
        entries: List<T>,
        dateOf: (T) -> LocalDate,
        shelfLifeDays: Int,
        today: LocalDate
    ): List<List<T>> {
        val sorted = entries.sortedBy(dateOf)
        val batches = mutableListOf<MutableList<T>>()
        var buyOn: LocalDate? = null
        for (entry in sorted) {
            val date = dateOf(entry)
            val current = buyOn
            if (current == null || date.isAfter(current.plusDays(shelfLifeDays.toLong() - 1))) {
                buyOn = buyDateFor(date, today)
                batches.add(mutableListOf(entry))
            } else {
                batches.last().add(entry)
            }
        }
        return batches
    }

    /** The day a list row should be bought: today unless it is a fresh item needed later. */
    fun tripDate(name: String, mealDates: List<String>, today: LocalDate): LocalDate {
        if (shelfLifeDays(name) == null) return today
        val first = mealDates.mapNotNull { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }.minOrNull()
            ?: return today
        return buyDateFor(first, today)
    }

    /** Buy the day before the first meal that needs it, but never in the past. */
    fun buyDateFor(firstMeal: LocalDate, today: LocalDate): LocalDate {
        val day = firstMeal.minusDays(1)
        return if (day.isBefore(today)) today else day
    }

    private val LONG_LIFE_WORDS = listOf(
        "frozen", "tinned", "canned", "dried", "dry ", "jar", "powder", "stock cube", "sauce",
        "paste", "oil", "vinegar", "flour", "sugar", "rice", "pasta", "noodle", "lentil", "beans",
        "tin of", "ground", "spice", "salt", "black pepper", "juice", "puree", "ketchup", "mustard",
        "mayonnaise", "dressing", "extract", "syrup", "honey", "seasoning", "stock", "concentrate"
    )

    private val RULES: List<Pair<List<String>, Int>> = listOf(
        listOf("fish", "salmon", "cod", "haddock", "prawn", "shrimp", "tuna steak", "seafood", "mussel", "sea bass", "trout") to 2,
        listOf("mince", "sausage", "chicken", "turkey", "duck") to 2,
        listOf("beef", "pork", "lamb", "steak", "bacon", "ham", "gammon") to 3,
        listOf("raspberr", "strawberr", "blueberr", "blackberr", "berries") to 3,
        listOf("salad", "lettuce", "rocket", "spinach", "watercress", "coriander", "basil", "parsley", "mint", "dill", "chive", "herb") to 3,
        listOf("avocado", "mushroom", "courgette", "asparagus", "broccoli", "cauliflower", "green bean") to 4,
        listOf("bread", "bun", "roll", "wrap", "tortilla", "naan", "pitta") to 4,
        listOf("milk", "cream", "yoghurt", "yogurt", "fromage", "crème") to 5,
        listOf("tofu", "fresh pasta", "fresh egg", "hummus") to 5,
        listOf("tomato", "cucumber", "pepper", "lemon", "lime", "apple", "banana", "pear") to 6
    )
}
