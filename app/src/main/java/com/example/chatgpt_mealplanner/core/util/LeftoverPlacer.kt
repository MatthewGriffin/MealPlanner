package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.MealType
import java.time.LocalDate

/** Where a batch's extra portions go: whole plan slots they fill, and the portions left to freeze. */
data class LeftoverPlacement(val slots: List<PlanSlot>, val frozenPortions: Int) {
}

object LeftoverPlacer {
    /**
     * A recipe that cannot be scaled down (a 4-portion kit cooked for 2) makes [extraPortions] more
     * than the household eats now. Each later slot of the same meal type in the requested plan
     * takes [servings] portions; whatever does not fill a whole slot is frozen.
     */
    fun place(
        extraPortions: Int,
        servings: Int,
        mealDate: LocalDate,
        mealType: MealType?,
        remainingSlots: List<PlanSlot>
    ): LeftoverPlacement {
        if (extraPortions <= 0 || servings <= 0) return LeftoverPlacement(emptyList(), 0)
        val fits = extraPortions / servings
        val chosen = remainingSlots
            .filter { (mealType == null || it.mealType == mealType) && it.date.isAfter(mealDate) }
            .sortedBy { it.date }
            .take(fits)
        return LeftoverPlacement(chosen, extraPortions - chosen.size * servings)
    }
}
