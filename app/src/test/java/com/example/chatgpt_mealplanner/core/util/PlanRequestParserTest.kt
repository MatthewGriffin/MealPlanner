package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PlanRequestParserTest {
    private val friday = LocalDate.of(2026, 11, 13)

    @Test fun nextWeekIsSevenDinnersFromMonday() {
        val s = PlanRequestParser.parse("make me a meal plan for next week", friday)!!
        assertEquals(7, s.size)
        assertEquals(LocalDate.of(2026, 11, 16), s.first().date)
        assertEquals(LocalDate.of(2026, 11, 22), s.last().date)
        assertEquals(setOf(MealType.DINNER), s.map { it.mealType }.toSet())
    }

    @Test fun threeDinnersStartToday() {
        val s = PlanRequestParser.parse("plan 3 dinners", friday)!!
        assertEquals(listOf(friday, friday.plusDays(1), friday.plusDays(2)), s.map { it.date })
    }

    @Test fun lunchAndDinnerForThreeDays() {
        val s = PlanRequestParser.parse("plan lunch and dinner for 3 days", friday)!!
        assertEquals(6, s.size)
        assertEquals(listOf(MealType.LUNCH, MealType.DINNER), s.take(2).map { it.mealType })
    }

    @Test fun thisWeekIsRestOfWeek() {
        assertEquals(3, PlanRequestParser.parse("plan dinners for this week", friday)!!.size) // Fri, Sat, Sun
    }

    @Test fun weekendIsTwoDays() {
        assertEquals(2, PlanRequestParser.parse("plan the weekend", friday)!!.size)
    }

    @Test fun singleMealIsNotAPlan() {
        assertNull(PlanRequestParser.parse("plan today's dinner", friday))
        assertNull(PlanRequestParser.parse("what's for dinner", friday))
        assertNull(PlanRequestParser.parse("plan 1 dinner", friday))
    }
}
