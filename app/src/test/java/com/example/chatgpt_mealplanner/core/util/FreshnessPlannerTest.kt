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

    // today is Fri 13 Nov 2026; the meal days below are Thu 19 to Sun 22 Nov
    private val thu19 = "2026-11-19"; private val fri20 = "2026-11-20"; private val sat21 = "2026-11-21"; private val sun22 = "2026-11-22"
    private fun need(date: String, life: Int?) = FreshnessPlanner.TripNeed(listOf(date), life)

    @Test fun threeFreshItemsForConsecutiveDaysAreBoughtInOneTrip() {
        // Needed Fri, Sat, Sun, each keeps 3 days: all bought Thursday, not on three different days.
        val days = FreshnessPlanner.planTrips(listOf(need(fri20, 3), need(sat21, 3), need(sun22, 3)), today)
        assertEquals(1, days.toSet().size)
        assertEquals(LocalDate.of(2026, 11, 19), days.first())
    }

    @Test fun anItemThatWouldNotKeepLongEnoughGetsALaterTrip() {
        // A 2 day item for Sunday cannot be bought Thursday (good only to Saturday), so it gets Saturday's trip.
        val days = FreshnessPlanner.planTrips(listOf(need(fri20, 3), need(sun22, 2)), today)
        assertEquals(LocalDate.of(2026, 11, 19), days[0])
        assertEquals(LocalDate.of(2026, 11, 21), days[1])
    }

    @Test fun aLaterTripAlsoTakesWhateverIsNotNeededBeforeIt() {
        // Rice is needed on Sunday, so it is bought on the later trip with the fresh fish rather than today.
        val days = FreshnessPlanner.planTrips(listOf(need(fri20, 3), need(sun22, 2), need(sun22, null), need(thu19, null)), today)
        assertEquals(LocalDate.of(2026, 11, 21), days[1])
        assertEquals(LocalDate.of(2026, 11, 21), days[2])
        assertEquals("needed Thursday: bought today", today, days[3])
    }

    @Test fun withNoLaterTripEverythingIsBoughtToday() {
        val days = FreshnessPlanner.planTrips(listOf(need("2026-11-14", 3), need(sat21, null), FreshnessPlanner.TripNeed(emptyList(), null)), today)
        assertEquals(today, days[0])
        assertEquals("a trip today exists, so the long-life item is bought on it", today, days[1])
        assertEquals(today, days[2])
    }
}
