package com.example.chatgpt_mealplanner.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepTimersTest {
    private fun secs(text: String) = StepTimers.find(text).map { it.seconds }

    @Test fun plainMinutes() = assertEquals(listOf(600), secs("Simmer gently for 10 minutes, stirring."))
    @Test fun abbreviations() = assertEquals(listOf(1200), secs("Bake 20 mins until golden."))
    @Test fun hours() = assertEquals(listOf(7200), secs("Slow cook for 2 hours."))
    @Test fun hoursAndMinutes() = assertEquals(listOf(5400), secs("Roast for 1 hour 30 minutes."))
    @Test fun halfHourWords() = assertEquals(listOf(1800), secs("Leave to rest for half an hour."))
    @Test fun anHour() = assertEquals(listOf(3600), secs("Chill for an hour."))
    @Test fun fractions() = assertEquals(listOf(5400), secs("Cook for 1½ hours."))
    @Test fun rangeUsesTheShorterTime() {
        val t = StepTimers.find("Fry for 10-15 minutes.").single()
        assertEquals(600, t.seconds)
        assertEquals("10-15 minutes", t.label)
    }
    @Test fun rangeWithTo() = assertEquals(listOf(1200), secs("Bake for 20 to 25 minutes."))
    @Test fun twoTimesInOneStep() = assertEquals(listOf(1200, 300), secs("Bake 20 minutes, then rest 5 minutes."))
    @Test fun seconds() = assertEquals(listOf(30), secs("Blitz for 30 seconds."))

    @Test fun notTimes() {
        assertTrue(StepTimers.find("Add 2 tablespoons of oil and a pinch of salt.").isEmpty())
        assertTrue(StepTimers.find("Preheat the oven to 200C.").isEmpty())
        assertTrue(StepTimers.find("Cut into 5 cm pieces and add 250 ml stock.").isEmpty())
        assertEquals(listOf(60), secs("Cook for a minute."))
    }

    @Test fun theStepsOwnTagIsUsedWhenTheTextHasNoTime() {
        assertEquals(listOf(480), StepTimers.forStep("Cook until tender.", 8).map { it.seconds })
        assertEquals(listOf(600), StepTimers.forStep("Simmer for 10 minutes.", 8).map { it.seconds })
        assertTrue(StepTimers.forStep("Season well.", null).isEmpty())
    }
}
