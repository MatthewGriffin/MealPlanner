package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.MealType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class LeftoverPlacerTest {
    private val mon = LocalDate.of(2026, 11, 16)
    private fun dinners(vararg offsets: Long) = offsets.map { PlanSlot(mon.plusDays(it), MealType.DINNER) }

    @Test fun kitForFourCookedForTwoFillsOneLaterDay() {
        val p = LeftoverPlacer.place(2, 2, mon, MealType.DINNER, dinners(1, 2, 3))
        assertEquals(listOf(mon.plusDays(1)), p.slots.map { it.date })
        assertEquals(0, p.frozenPortions)
    }

    @Test fun overflowIsFrozen() {
        val p = LeftoverPlacer.place(6, 2, mon, MealType.DINNER, dinners(1))
        assertEquals(1, p.slots.size)
        assertEquals(4, p.frozenPortions)
    }

    @Test fun oddPortionsLeftOverAreFrozen() {
        val p = LeftoverPlacer.place(3, 2, mon, MealType.DINNER, dinners(1, 2))
        assertEquals(1, p.slots.size)
        assertEquals(1, p.frozenPortions)
    }

    @Test fun noPlanMeansFreeze() {
        val p = LeftoverPlacer.place(2, 2, mon, MealType.DINNER, emptyList())
        assertEquals(0, p.slots.size)
        assertEquals(2, p.frozenPortions)
    }

    @Test fun onlySameMealTypeAfterTheMealDate() {
        val slots = listOf(
            PlanSlot(mon, MealType.DINNER),
            PlanSlot(mon.plusDays(1), MealType.LUNCH),
            PlanSlot(mon.plusDays(2), MealType.DINNER)
        )
        val p = LeftoverPlacer.place(2, 2, mon, MealType.DINNER, slots)
        assertEquals(listOf(mon.plusDays(2)), p.slots.map { it.date })
    }

    @Test fun nothingExtra() {
        val p = LeftoverPlacer.place(0, 2, mon, MealType.DINNER, dinners(1))
        assertEquals(0, p.slots.size); assertEquals(0, p.frozenPortions)
    }

    @Test fun nullMealTypeAcceptsAnyProvidedSlot() {
        val slots = listOf(PlanSlot(mon.plusDays(1), MealType.LUNCH))
        val p = LeftoverPlacer.place(2, 2, mon, null, slots)
        assertEquals(1, p.slots.size)
    }
}
