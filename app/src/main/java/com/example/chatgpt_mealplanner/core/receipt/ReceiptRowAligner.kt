package com.example.chatgpt_mealplanner.core.receipt

import kotlin.math.abs

internal data class ReceiptRowAlignment(
    /** Description line index to the index of its price line. */
    val priceLineFor: Map<Int, Int>,
    val priceOnlyLines: Set<Int>,
    val rowTolerance: Int
)

/**
 * Pairs the description and price columns of a receipt photo.
 *
 * Tilt and creases move a price up or down relative to its description, sometimes closer to the
 * neighbouring row. They don't change the order of the rows, so the columns are matched in order,
 * using the vertical offset that pairs the most rows. Distances scale with the photo's row height.
 */
internal object ReceiptRowAligner {
    private val priceOnly = Regex("""^-?\s*[£CLegqPS$€]?\s*-?\d{1,3}\.\d{2}\s*[A-Za-z*]?$""", RegexOption.IGNORE_CASE)

    fun isPriceOnly(text: String): Boolean = priceOnly.matches(text.trim())

    fun align(lines: List<ReceiptOcrLine>): ReceiptRowAlignment {
        val prices = lines.indices.filter { isPriceOnly(lines[it].text) }.sortedBy { lines[it].centerY }
        val descriptions = lines.indices.filter { i ->
            i !in prices && lines[i].text.count { it.isLetter() } >= 2 &&
                ReceiptMoneyParser.parseRightAlignedAmountPence(lines[i].text) == null
        }.sortedBy { lines[it].centerY }

        val pitch = medianGap(prices.map { lines[it].centerY })
        val tolerance = (pitch * 45 / 100).coerceAtLeast(1)
        val offsets = buildSet {
            add(0)
            descriptions.forEach { d ->
                prices.forEach { p ->
                    val offset = lines[p].centerY - lines[d].centerY
                    if (abs(offset) <= pitch * 5 / 2) add(offset)
                }
            }
        }

        var best: List<Pair<Int, Int>> = emptyList()
        var bestCost = Long.MAX_VALUE
        offsets.sortedBy { abs(it) }.forEach { offset ->
            val pairs = matchInOrder(lines, descriptions, prices, offset, tolerance)
            val cost = pairs.sumOf { (d, p) -> abs(lines[p].centerY - lines[d].centerY - offset).toLong() }
            if (pairs.size > best.size || (pairs.size == best.size && cost < bestCost)) {
                best = pairs
                bestCost = cost
            }
        }
        return ReceiptRowAlignment(best.toMap(), prices.toSet(), tolerance)
    }

    private fun matchInOrder(
        lines: List<ReceiptOcrLine>,
        descriptions: List<Int>,
        prices: List<Int>,
        offset: Int,
        tolerance: Int
    ): List<Pair<Int, Int>> {
        fun matches(d: Int, p: Int) = lines[p].left > lines[d].left &&
            abs(lines[p].centerY - lines[d].centerY - offset) <= tolerance

        val scores = Array(descriptions.size + 1) { IntArray(prices.size + 1) }
        for (i in descriptions.indices.reversed()) {
            for (j in prices.indices.reversed()) {
                val paired = if (matches(descriptions[i], prices[j])) scores[i + 1][j + 1] + 1 else 0
                scores[i][j] = maxOf(paired, scores[i + 1][j], scores[i][j + 1])
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < descriptions.size && j < prices.size) {
            when {
                matches(descriptions[i], prices[j]) && scores[i][j] == scores[i + 1][j + 1] + 1 -> {
                    pairs += descriptions[i] to prices[j]
                    i++
                    j++
                }
                scores[i][j] == scores[i + 1][j] -> i++
                else -> j++
            }
        }
        return pairs
    }

    private fun medianGap(sortedYs: List<Int>): Int {
        val gaps = sortedYs.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        return if (gaps.isEmpty()) 0 else gaps[gaps.size / 2]
    }
}
