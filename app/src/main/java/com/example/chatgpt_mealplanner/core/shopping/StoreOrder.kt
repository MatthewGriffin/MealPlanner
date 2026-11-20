package com.example.chatgpt_mealplanner.core.shopping

import com.example.chatgpt_mealplanner.core.util.IngredientNameMatcher

/** The learning and sorting rules for a shop's order, kept free of storage so they are easy to test. */
object StoreOrder {
    /** The name of a list item reduced to what identifies it, so "Diced chicken breast" and "chicken breast, diced" are one. */
    fun keyOf(displayName: String): String = IngredientNameMatcher.core(displayName)

    /** How far round the shop each item came: the first ticked is 0.0 and the last is 1.0. A lone item is in the middle. */
    fun ranksOf(tickedKeysInOrder: List<String>): Map<String, Double> {
        val distinct = tickedKeysInOrder.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        if (distinct.size == 1) return mapOf(distinct.first() to 0.5)
        return distinct.mapIndexed { index, key -> key to index.toDouble() / (distinct.size - 1) }.toMap()
    }

    /** Folds one more trip's rank into the running average. */
    fun mergeRank(oldAverage: Double, oldSamples: Int, newRank: Double): Double =
        (oldAverage * oldSamples + newRank) / (oldSamples + 1)

    /**
     * Items to buy in the order this shop is walked: items it has learned first, by average position; unknown
     * items after them, in the order given.
     */
    fun <T> sort(items: List<T>, nameOf: (T) -> String, orderByKey: Map<String, Double>): List<T> {
        if (orderByKey.isEmpty()) return items
        val (known, unknown) = items.partition { orderByKey.containsKey(keyOf(nameOf(it))) }
        return known.sortedBy { orderByKey.getValue(keyOf(nameOf(it))) } + unknown
    }
}
