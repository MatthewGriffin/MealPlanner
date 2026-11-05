package com.example.chatgpt_mealplanner.core.receipt

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A detail row belonging to a neighbouring product, such as "2 X 1.50" or "0.812 KG @ £0.90/KG".
 * It is never a product itself.
 */
internal data class ReceiptSubLine(
    val quantity: Int,
    val unitPricePence: Long?,
    val linePricePence: Long
)

internal object ReceiptSubLineParser {
    private const val PRICE = """[£CLegqPS$€]?\s*(\d{1,3}[.,]\d{2})"""
    private const val TRAILING_VAT_LETTER = """(?:\s+[A-Za-z*])?"""

    private val multibuy = Regex(
        """^(\d{1,3})\s*[X*@]\s*$PRICE(?:\s*(?:EA|EACH))?(?:\s+$PRICE)?$TRAILING_VAT_LETTER$""",
        RegexOption.IGNORE_CASE
    )
    private val weighed = Regex(
        """^(\d{1,3}(?:[.,]\d{1,3})?)\s*(KG|G|LB)\s*([@X*]|AT)?\s*$PRICE\s*(/\s*(?:KG|LB)|PER\s*(?:KG|LB))?(?:\s+$PRICE)?$TRAILING_VAT_LETTER$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): ReceiptSubLine? {
        val trimmed = text.trim()
        return parseMultibuy(trimmed) ?: parseWeighed(trimmed)
    }

    private fun parseMultibuy(text: String): ReceiptSubLine? {
        val (quantityText, unitText, lineText) = multibuy.matchEntire(text)?.destructured ?: return null
        val quantity = quantityText.toInt().takeIf { it > 0 } ?: return null
        val unit = pence(unitText)
        return ReceiptSubLine(quantity, unit, if (lineText.isEmpty()) unit * quantity else pence(lineText))
    }

    /** A weight only counts with "@" or "/KG" beside it, because "500G 1.50" could be a product. */
    private fun parseWeighed(text: String): ReceiptSubLine? {
        val (weightText, unitOfWeight, separator, rateText, per, lineText) =
            weighed.matchEntire(text)?.destructured ?: return null
        if (separator.isEmpty() && per.isEmpty()) return null
        val linePence = if (lineText.isNotEmpty()) {
            pence(lineText)
        } else {
            val weight = BigDecimal(weightText.replace(',', '.'))
                .let { if (unitOfWeight.equals("G", ignoreCase = true)) it.movePointLeft(3) else it }
            weight.multiply(BigDecimal(pence(rateText))).setScale(0, RoundingMode.HALF_UP).toLong()
        }
        return ReceiptSubLine(quantity = 1, unitPricePence = null, linePricePence = linePence)
    }

    private fun pence(text: String): Long = BigDecimal(text.replace(',', '.')).movePointRight(2).toLong()
}
