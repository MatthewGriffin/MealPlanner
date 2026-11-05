package com.example.chatgpt_mealplanner.core.receipt

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Removes a photo's tilt from OCR positions.
 *
 * Near 3 degrees a price sits a whole row away from its description, which looks just like correct
 * alignment, so the columns alone can't reveal the tilt. Words that OCR read as one line can: they
 * belong to the same row, so the slope between them is the slope of the receipt.
 */
internal object ReceiptDeskew {
    /** About 10 degrees. Steeper readings are OCR joining separate rows, not tilt. */
    private const val MAX_SLOPE = 0.18

    /** About 0.1 degrees, which moves a price less than a pixel or two. */
    private const val MIN_SLOPE = 0.002

    private const val MIN_SPAN_IN_LINE_HEIGHTS = 4

    fun straighten(result: ReceiptOcrResult): ReceiptOcrResult {
        val slope = estimateSlope(result.lines) ?: return result
        if (abs(slope) < MIN_SLOPE) return result

        val originX = result.lines.minOf { it.left }
        fun shift(left: Int, right: Int) = -(slope * ((left + right) / 2 - originX)).roundToInt()

        val straightened = result.lines.map { line ->
            val dy = shift(line.left, line.right)
            line.copy(
                top = line.top + dy,
                bottom = line.bottom + dy,
                centerY = line.centerY + dy,
                elements = line.elements.map { element ->
                    val elementDy = shift(element.left, element.right)
                    element.copy(
                        top = element.top + elementDy,
                        bottom = element.bottom + elementDy,
                        centerY = element.centerY + elementDy
                    )
                }
            )
        }.sortedWith(compareBy<ReceiptOcrLine> { it.centerY }.thenBy { it.left })
            .mapIndexed { index, line -> line.copy(lineIndex = index) }

        return result.copy(lines = straightened)
    }

    /** The span-weighted median slope across multi-word lines, or null if no line gives evidence. */
    fun estimateSlope(lines: List<ReceiptOcrLine>): Double? {
        val samples = lines.mapNotNull { line ->
            val boxes = line.elements.filter { it.right > it.left && it.bottom > it.top }
            val first = boxes.minByOrNull { it.left } ?: return@mapNotNull null
            val last = boxes.maxByOrNull { it.right } ?: return@mapNotNull null
            val span = (last.left + last.right - first.left - first.right) / 2
            val height = first.bottom - first.top
            if (span < height * MIN_SPAN_IN_LINE_HEIGHTS) return@mapNotNull null
            val slope = (last.centerY - first.centerY).toDouble() / span
            if (abs(slope) > MAX_SLOPE) null else slope to span
        }.sortedBy { it.first }
        if (samples.isEmpty()) return null

        val halfWeight = samples.sumOf { it.second } / 2
        var weight = 0
        return samples.first { (_, span) ->
            weight += span
            weight >= halfWeight
        }.first
    }
}
