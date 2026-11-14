package com.example.chatgpt_mealplanner.core.util

import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class FreshnessPlannerTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test fun freshChickenIsPerishableButTinnedIsNot() {
        assertEquals(2, FreshnessPlanner.shelfLifeDays("Chicken breast"))
        assertNull(FreshnessPlanner.shelfLifeDays("Tinned tomatoes"))
        assertNull(FreshnessPlanner.shelfLifeDays("Frozen chicken"))
        assertNull(FreshnessPlanner.shelfLifeDays("Rice"))
    }

    @Test fun shelfStableLiquidsAndSaucesAreNeverSplit() {
        assertNull(FreshnessPlanner.shelfLifeDays("Lemon juice"))
        assertNull(FreshnessPlanner.shelfLifeDays("Lime juice"))
        assertNull(FreshnessPlanner.shelfLifeDays("Chicken stock"))
        assertEquals(6, FreshnessPlanner.shelfLifeDays("Lemon"))
    }

    @Test fun mealsFarApartAreSplitIntoTrips() {
        val dates = listOf(today, today.plusDays(1), today.plusDays(6))
        val batches = FreshnessPlanner.batchByShelfLife(dates, { it }, 2, today)
        assertEquals(listOf(listOf(today, today.plusDays(1)), listOf(today.plusDays(6))), batches)
    }

    @Test fun laterTripIsBoughtTheDayBeforeTheMeal() {
        assertEquals(today.plusDays(5), FreshnessPlanner.tripDate("Chicken", listOf("2026-11-19"), today))
        assertEquals(today, FreshnessPlanner.tripDate("Rice", listOf("2026-11-19"), today))
        assertEquals(today, FreshnessPlanner.tripDate("Chicken", listOf("2026-11-13"), today))
    }
}
