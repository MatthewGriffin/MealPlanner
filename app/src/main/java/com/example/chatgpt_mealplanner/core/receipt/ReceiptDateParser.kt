package com.example.chatgpt_mealplanner.core.receipt

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/** Reads the purchase date from receipt text in the numeric and written styles used in different countries. */
object ReceiptDateParser {
    private val isoStyle = Regex("""\b(20\d{2})[/.-](\d{1,2})[/.-](\d{1,2})\b""")
    private val numeric = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](20\d{2}|\d{2})\b""")
    private val dayMonthName = Regex("""\b(\d{1,2})\.?\s*(?:of\s+)?([A-Za-zÀ-ÿ]{3,9})\.?,?\s+(20\d{2}|\d{2})\b""")
    private val monthNameDay = Regex("""\b([A-Za-zÀ-ÿ]{3,9})\.?\s+(\d{1,2}),?\s+(20\d{2})\b""")

    // First three letters (accents removed) of month names in English, German, French, Spanish, Italian and Dutch.
    private val months: Map<String, Int> = mapOf(
        "jan" to 1, "ene" to 1, "gen" to 1, "feb" to 2, "fev" to 2, "mar" to 3, "mrt" to 3, "apr" to 4, "avr" to 4, "abr" to 4,
        "may" to 5, "mai" to 5, "mei" to 5, "mag" to 5, "jun" to 6, "giu" to 6, "jul" to 7, "juil" to 7, "lug" to 7,
        "aug" to 8, "aou" to 8, "ago" to 8, "sep" to 9, "set" to 9, "oct" to 10, "okt" to 10, "ott" to 10, "nov" to 11,
        "dec" to 12, "dez" to 12, "dic" to 12
    )

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** True where the region writes the month before the day (the United States, the Philippines). */
    private fun monthFirst(locale: Locale): Boolean = locale.country in setOf("US", "PH")

    private fun date(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(if (year < 100) 2000 + year else year, month, day) }.getOrNull()?.takeIf { it.year in 2000..2100 }

    fun parse(text: String, locale: Locale = Locale.getDefault()): LocalDate? {
        isoStyle.findAll(text).firstNotNullOfOrNull { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }?.let { return it }

        numeric.findAll(text).firstNotNullOfOrNull { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = m.groupValues[3].toInt()
            when {
                a > 12 -> date(year, b, a)               // 27/09/2026 can only be day first
                b > 12 -> date(year, a, b)               // 09/27/2026 can only be month first
                monthFirst(locale) -> date(year, a, b)   // ambiguous: follow the region
                else -> date(year, b, a)
            }
        }?.let { return it }

        dayMonthName.findAll(text).firstNotNullOfOrNull { m ->
            val month = months[fold(m.groupValues[2]).take(3)] ?: return@firstNotNullOfOrNull null
            date(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())
        }?.let { return it }

        return monthNameDay.findAll(text).firstNotNullOfOrNull { m ->
            val month = months[fold(m.groupValues[1]).take(3)] ?: return@firstNotNullOfOrNull null
            date(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())
        }
    }
}
