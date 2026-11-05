package com.example.chatgpt_mealplanner.core.receipt

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.tan

class ReceiptRowAlignerTest {

    private fun line(text: String, left: Int, centerY: Int) = ReceiptOcrLine(
        text, 0.7f, 0, left = left, top = centerY - 10, right = left + 200, bottom = centerY + 10, centerY = centerY
    )

    /** Positions read by ML Kit from a real crumpled Asda receipt, where prices sit above their rows. */
    @Test
    fun realCrumpledReceipt_pairsEachPriceWithItsOwnRow() {
        val lines = listOf(
            line("£1.94", 1771, 484), line("SOFT DRINK", 1288, 502),
            line("5.34", 1771, 513), line("CRISP N DRY", 1290, 529),
            line("23.74", 1775, 540), line("ERNIE PIZZA", 1292, 559),
            line("£3.74", 1784, 573), line("CHIKN GRILLS", 1296, 587)
        )

        val draft = ReceiptSpendParser.parseOcrResult(ReceiptOcrResult(lines, "ASDA"), null)

        assertEquals(
            listOf("SOFT DRINK" to 194L, "CRISP N DRY" to 534L, "ERNIE PIZZA" to 2374L, "CHIKN GRILLS" to 374L),
            draft.items.map { it.normalizedDescription to it.lineAmountMinorPence }
        )
    }

    private fun transformed(degrees: Double, scale: Double): ReceiptOcrResult {
        val base = AsdaPhysicalReceiptOcrFixture.createRealDevice79LineOcrResult()
        val slope = tan(Math.toRadians(degrees))
        fun y(value: Int, x: Int) = ((value + x * slope) * scale).toInt()
        val lines = base.lines.map { l ->
            val cx = (l.left + l.right) / 2
            l.copy(
                left = (l.left * scale).toInt(), right = (l.right * scale).toInt(),
                top = y(l.top, cx), bottom = y(l.bottom, cx), centerY = y(l.centerY, cx),
                elements = l.elements.map { e ->
                    val ex = (e.left + e.right) / 2
                    e.copy(
                        left = (e.left * scale).toInt(), right = (e.right * scale).toInt(),
                        top = y(e.top, ex), bottom = y(e.bottom, ex), centerY = y(e.centerY, ex)
                    )
                }
            )
        }.sortedWith(compareBy<ReceiptOcrLine> { it.centerY }.thenBy { it.left })
        return ReceiptOcrResult(lines.mapIndexed { i, l -> l.copy(lineIndex = i) }, base.fullText)
    }

    @Test
    fun tiltedPhotos_keepEveryItemAndTheTotal() {
        listOf(-3.0, -2.0, -1.0, -0.5, 0.5, 1.0, 1.5, 2.0, 3.0).forEach { degrees ->
            val draft = ReceiptSpendParser.parseOcrResult(transformed(degrees, 1.0), null)
            assertEquals("items at $degrees°", 12, draft.items.size)
            assertEquals("paid at $degrees°", 3888L, draft.totalMinorPence)
        }
    }

    @Test
    fun differentPhotoResolutions_keepEveryItemAndTheTotal() {
        listOf(0.5, 0.75, 1.5, 2.0, 3.0, 4.0).forEach { scale ->
            val draft = ReceiptSpendParser.parseOcrResult(transformed(0.0, scale), null)
            assertEquals("items at x$scale", 12, draft.items.size)
            assertEquals("paid at x$scale", 3888L, draft.totalMinorPence)
        }
    }
}
