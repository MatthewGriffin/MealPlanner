package com.example.chatgpt_mealplanner.core.util

/** A wait or cook time found in a recipe step, as something a phone timer can count down. */
data class StepTimer(
    /** What the step says, e.g. "10-15 minutes". */
    val label: String,
    /** The timer length. For a range ("10-15 minutes") this is the shorter time, so nothing overcooks; check at that point. */
    val seconds: Int
)

object StepTimers {
    private const val NUM = """(\d+(?:[.,]\d+)?|\d*\s?[½¼¾]|an?|one|two|three|four|five|six|seven|eight|nine|ten|half an?)"""
    private const val UNIT = """(hours?|hrs?|minutes?|mins?|seconds?|secs?)"""
    // "1 hour 30 minutes", "10-15 minutes", "20 mins", "half an hour"; the unit must be a whole word.
    private val duration = Regex(
        """(?<![\p{L}\p{N}.])(?:$NUM(?:\s?(?:-|–|to)\s?$NUM)?\s?$UNIT(?![\p{L}])(?:\s?(?:and\s)?$NUM\s?(?:minutes?|mins?)(?![\p{L}]))?)""",
        RegexOption.IGNORE_CASE
    )
    private val words = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "half a" to 0.5, "half an" to 0.5
    )

    private fun value(raw: String): Double? {
        val t = raw.trim().lowercase()
        words[t]?.let { return it }
        val frac = when {
            t.endsWith("½") -> 0.5
            t.endsWith("¼") -> 0.25
            t.endsWith("¾") -> 0.75
            else -> null
        }
        if (frac != null) return (t.dropLast(1).trim().toDoubleOrNull() ?: 0.0) + frac
        return t.replace(',', '.').toDoubleOrNull()
    }

    private fun secondsPer(unit: String): Int = when (unit.lowercase().first()) {
        'h' -> 3600
        'm' -> 60
        else -> 1
    }

    /** Every time in the step, in the order written ("sear 2 minutes a side" gives one; "bake 20 minutes, rest 5 minutes" two). */
    fun find(stepText: String): List<StepTimer> =
        duration.findAll(stepText).mapNotNull { m ->
            val g = m.groupValues
            val first = value(g[1]) ?: return@mapNotNull null
            var seconds = first * secondsPer(g[3])
            val extraMinutes = value(g[4])
            if (extraMinutes != null && g[3].lowercase().startsWith("h")) seconds += extraMinutes * 60
            val total = seconds.toInt()
            if (total < 5 || total > 24 * 3600) null else StepTimer(m.value.trim(), total)
        }.distinctBy { it.seconds to it.label.lowercase() }.toList()

    /** Timers for a step: those written in its text, or else the step's own "(N min)" tag. */
    fun forStep(text: String, taggedMinutes: Int?): List<StepTimer> {
        val found = find(text)
        if (found.isNotEmpty()) return found
        return if (taggedMinutes != null && taggedMinutes > 0) listOf(StepTimer("$taggedMinutes min", taggedMinutes * 60)) else emptyList()
    }
}
