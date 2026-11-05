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
 * neighbouring row. They don't change the order of the rows, so the columns are matched in order.
 * Distances scale with the photo's row height.
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
        return ReceiptRowAlignment(longestChain(lines, descriptions, prices, pitch).toMap(), prices.toSet(), tolerance)
    }

    /**
     * Finds the longest in-order run of description and price pairs whose vertical offset only
     * changes gradually. Creases can drift that offset by more than a row across a whole receipt,
     * but only a little between neighbouring rows. A chain shifted by one row leaves the first price
     * without a description, so ties go to the chain starting at the earliest price, then to the
     * smallest total offset.
     */
    private fun longestChain(
        lines: List<ReceiptOcrLine>,
        descriptions: List<Int>,
        prices: List<Int>,
        pitch: Int
    ): List<Pair<Int, Int>> {
        class Candidate(val d: Int, val p: Int, val offset: Int, val y: Int) {
            var length = 1
            var cost = abs(offset).toLong()
            var previous: Candidate? = null
            var startPrice = p
        }
        val maxOffset = pitch * 5 / 2
        val candidates = descriptions.indices.flatMap { d ->
            prices.indices.mapNotNull { p ->
                val descLine = lines[descriptions[d]]
                val priceLine = lines[prices[p]]
                val offset = priceLine.centerY - descLine.centerY
                if (priceLine.left > descLine.left && abs(offset) <= maxOffset) Candidate(d, p, offset, descLine.centerY) else null
            }
        }
        val driftPerRow = (pitch * 35 / 100).coerceAtLeast(1)
        var best: Candidate? = null
        candidates.forEachIndexed { index, current ->
            for (m in 0 until index) {
                val earlier = candidates[m]
                if (earlier.d >= current.d || earlier.p >= current.p) continue
                val rows = if (pitch > 0) ((current.y - earlier.y) / pitch).coerceAtLeast(1) else 1
                if (abs(current.offset - earlier.offset) > driftPerRow * rows) continue
                val length = earlier.length + 1
                val cost = earlier.cost + abs(current.offset)
                if (length > current.length ||
                    (length == current.length && earlier.startPrice < current.startPrice) ||
                    (length == current.length && earlier.startPrice == current.startPrice && cost < current.cost)
                ) {
                    current.startPrice = earlier.startPrice
                    current.length = length
                    current.cost = cost
                    current.previous = earlier
                }
            }
            val top = best
            if (top == null || current.length > top.length ||
                (current.length == top.length && current.startPrice < top.startPrice) ||
                (current.length == top.length && current.startPrice == top.startPrice && current.cost < top.cost)
            ) {
                best = current
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var node = best
        while (node != null) {
            pairs += descriptions[node.d] to prices[node.p]
            node = node.previous
        }
        return pairs
    }

    private fun medianGap(sortedYs: List<Int>): Int {
        val gaps = sortedYs.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        return if (gaps.isEmpty()) 0 else gaps[gaps.size / 2]
    }
}
