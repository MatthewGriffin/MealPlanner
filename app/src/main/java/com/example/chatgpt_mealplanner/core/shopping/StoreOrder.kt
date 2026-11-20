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

    /** A department's part of the list, in the order it is walked. */
    data class Section<T>(val department: Department, val items: List<T>)

    /**
     * Lays the list out as departments in walking order, each with its items. A department sits where its ticked items
     * average out in this shop; one not yet seen there takes its place from a typical supermarket's walk. Inside a
     * department, items the shop has taught the app come first by their own position, then the rest as listed.
     */
    fun <T> plan(items: List<T>, nameOf: (T) -> String, orderByKey: Map<String, Double>): List<Section<T>> {
        if (items.isEmpty()) return emptyList()
        val byDepartment = items.groupBy { Department.classify(nameOf(it)) }
        val learnedAverage = byDepartment.mapNotNull { (department, rows) ->
            val ranks = rows.mapNotNull { orderByKey[keyOf(nameOf(it))] }
            if (ranks.isEmpty()) null else department to ranks.average()
        }.toMap()
        val defaults = Department.entries.filter { it != Department.OTHER }
        fun walkPosition(department: Department): Double = learnedAverage[department]
            ?: if (department == Department.OTHER) 2.0 else defaults.indexOf(department).toDouble() / (defaults.size - 1)
        return byDepartment.entries
            .sortedBy { walkPosition(it.key) }
            .map { (department, rows) ->
                val (known, unknown) = rows.partition { orderByKey.containsKey(keyOf(nameOf(it))) }
                Section(department, known.sortedBy { orderByKey.getValue(keyOf(nameOf(it))) } + unknown)
            }
    }
}
