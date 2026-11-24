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
            // Bought the day before the first meal, an item is still good for shelfLifeDays after that day, so on a 3 day
            // life bought Thursday it is fine on Sunday.
            if (current == null || date.isAfter(current.plusDays(shelfLifeDays.toLong()))) {
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

    /** What a shopping row needs for trip planning: when its meals are, and how long it keeps (null: long enough not to matter). */
    data class TripNeed(val mealDates: List<String>, val shelfLifeDays: Int?)

    /**
     * Decides the day each row is bought, using as few shopping days as the freshness allows:
     *  - A fresh row can be bought on any day from (its last meal minus its shelf life) to the day before its first meal,
     *    so three fresh items for Friday, Saturday and Sunday can all be bought on Thursday if each keeps three days.
     *  - Shopping days are placed as late as possible (fresher), and a fresh row joins the latest shopping day that suits it.
     *  - Anything else (long-life items) whose first meal is after a later shopping day is bought on that day too, so a
     *    later trip is never just for one fresh item. What is needed sooner is bought today.
     * Returns the buy day for each index of [needs].
     */
    fun planTrips(needs: List<TripNeed>, today: LocalDate): List<LocalDate> {
        fun parse(dates: List<String>) = dates.mapNotNull { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        class Window(val index: Int, val earliest: LocalDate, val latest: LocalDate, val fresh: Boolean)
        val windows = needs.mapIndexed { i, need ->
            val dates = parse(need.mealDates)
            val first = dates.minOrNull()
            if (first == null) return@mapIndexed Window(i, today, today, false)
            val latest = buyDateFor(first, today)
            val life = need.shelfLifeDays
            val earliest = if (life == null) latest else {
                val e = dates.max().minusDays(life.toLong())
                if (e.isBefore(today)) today else if (e.isAfter(latest)) latest else e
            }
            Window(i, earliest, latest, fresh = life != null)
        }
        // Fewest shopping days that cover every fresh row's window, each placed on the latest day it can be.
        val trips = mutableListOf<LocalDate>()
        for (w in windows.filter { it.fresh }.sortedBy { it.latest }) {
            if (trips.none { !it.isBefore(w.earliest) && !it.isAfter(w.latest) }) trips.add(w.latest)
        }
        val result = MutableList(needs.size) { today }
        for (w in windows) {
            val fitting = trips.filter { !it.isBefore(w.earliest) && !it.isAfter(w.latest) }.maxOrNull()
            result[w.index] = when {
                w.fresh -> fitting ?: w.latest
                else -> trips.filter { !it.isAfter(w.latest) && !it.isBefore(today) }.maxOrNull() ?: today
            }
        }
        return result
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
