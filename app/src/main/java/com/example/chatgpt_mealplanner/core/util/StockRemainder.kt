package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.QuantityUnit

/**
 * Cooking that leaves only a sliver of an ingredient (1 g of pesto, 50 g of chicken) is treated as using it up:
 * nobody keeps that, so the app takes the lot instead of leaving a scrap in the pantry or on the shopping list.
 */
object StockRemainder {
    /** A leftover this small, as a share of what was in the lot, counts as used up. */
    const val SMALL_SHARE = 0.10

    private val measured = setOf(QuantityUnit.GRAM, QuantityUnit.KILOGRAM, QuantityUnit.MILLILITRE, QuantityUnit.LITRE)

    /** True when using [used] of a lot holding [inLot] would leave only a small remainder worth using up. */
    fun isSmallRemainder(inLot: Double, used: Double, unit: QuantityUnit?): Boolean {
        if (unit == null || unit !in measured) return false
        if (inLot <= 0.0 || used <= 0.0 || used >= inLot) return false
        return (inLot - used) <= inLot * SMALL_SHARE
    }
}
