package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.MealType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** One meal of a multi-meal plan the user asked for. */
data class PlanSlot(val date: LocalDate, val mealType: MealType)

/**
 * Works out which meals a plan request covers, so the app (not Claude) can count them and ask
 * for them one at a time. Returns null when the message is not a request for two or more meals.
 *
 * "plan 3 dinners" = three dinners from today; "meal plan for next week" = dinners Monday to
 * Sunday of next week; "plan breakfast and dinner for this weekend" = those meals on Saturday and Sunday.
 */
object PlanRequestParser {
    private const val MAX_SLOTS = 21
    private val NUMBER_WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7)
    private val COUNT = Regex("\\b(\\d+|one|two|three|four|five|six|seven)\\s+(breakfasts?|lunch(?:es)?|dinners?|suppers?|meals?|days?)\\b")
    private val MEAL_WORDS = listOf(
        MealType.BREAKFAST to Regex("\\bbreakfasts?\\b"),
        MealType.LUNCH to Regex("\\blunch(?:es)?\\b"),
        MealType.DINNER to Regex("\\b(dinners?|suppers?)\\b")
    )
    private val ALL_MEALS = Regex("\\b(all (my |the )?meals|every meal|meals a day|meals per day|three meals)\\b")

    fun parse(message: String, today: LocalDate = LocalDate.now()): List<PlanSlot>? {
        val text = message.lowercase()
        if (!Regex("\\bplan\\b").containsMatchIn(text)) return null

        val count = COUNT.find(text)
        val countNumber = count?.let { it.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[it.groupValues[1]] }
        val countIsDays = count?.groupValues?.get(2)?.startsWith("day") == true

        val types = when {
            ALL_MEALS.containsMatchIn(text) -> listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)
            else -> MEAL_WORDS.filter { it.second.containsMatchIn(text) }.map { it.first }.ifEmpty { listOf(MealType.DINNER) }
        }

        val days: List<LocalDate> = when {
            "next week" in text -> daysFrom(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), 7)
            "this week" in text || "rest of the week" in text ->
                daysFrom(today, 8 - today.dayOfWeek.value)
            "weekend" in text -> daysFrom(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)), 2)
            countIsDays && countNumber != null -> daysFrom(today, countNumber)
            Regex("\\b(a|the|for the|whole) week\\b").containsMatchIn(text) -> daysFrom(today, 7)
            countNumber != null -> daysFrom(today, countNumber)
            else -> return null
        }

        val all = days.flatMap { day -> types.map { PlanSlot(day, it) } }
        val slots = if (countNumber != null && !countIsDays) all.take(countNumber) else all
        return slots.take(MAX_SLOTS).takeIf { it.size >= 2 }
    }

    private fun daysFrom(start: LocalDate, count: Int): List<LocalDate> =
        (0 until count.coerceIn(0, 14)).map { start.plusDays(it.toLong()) }
}
