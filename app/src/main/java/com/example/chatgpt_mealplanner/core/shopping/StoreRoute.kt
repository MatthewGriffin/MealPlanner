package com.example.chatgpt_mealplanner.core.shopping

import kotlin.math.hypot

/** Turns what was learned of where items are in a shop into a walking order. */
object StoreRoute {
    /** Averages a new position into the running average of [samples] earlier ones. */
    fun mergePoint(old: ShopPoint?, samples: Int, new: ShopPoint): ShopPoint =
        if (old == null || samples <= 0) new
        else ShopPoint((old.x * samples + new.x) / (samples + 1), (old.y * samples + new.y) / (samples + 1))

    /**
     * The order to walk to items at [points], starting at the entrance (0, 0) and always going to the nearest one
     * not yet visited, as a position from 0.0 (first) to 1.0 (last) for each. A single item is in the middle.
     */
    fun rankByRoute(points: Map<String, ShopPoint>): Map<String, Double> {
        if (points.isEmpty()) return emptyMap()
        if (points.size == 1) return mapOf(points.keys.first() to 0.5)
        val remaining = points.toMutableMap()
        val order = mutableListOf<String>()
        var here = ShopPoint(0.0, 0.0)
        while (remaining.isNotEmpty()) {
            // Ties are broken by key so the same shop always gives the same walk.
            val next = remaining.entries.minWith(compareBy({ hypot(it.value.x - here.x, it.value.y - here.y) }, { it.key }))
            order += next.key
            here = next.value
            remaining.remove(next.key)
        }
        return order.mapIndexed { i, key -> key to i.toDouble() / (order.size - 1) }.toMap()
    }
}
