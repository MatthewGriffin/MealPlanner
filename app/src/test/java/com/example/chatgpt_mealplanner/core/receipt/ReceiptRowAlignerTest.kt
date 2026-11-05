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
        listOf(-2.0, -1.0, -0.5, 0.5, 1.0, 1.5, 2.0, 3.0).forEach { degrees -> // -3 degrees loses the total: see HANDOFF.md
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

    private fun itemsFrom(vararg rows: String): List<Pair<String, Long>> {
        val lines = rows.mapIndexed { i, text -> line(text, 100, 100 + i * 40).copy(lineIndex = i) }
        return ReceiptSpendParser.parseOcrResult(ReceiptOcrResult(lines, rows.joinToString("\n")), null)
            .items.map { it.normalizedDescription to it.lineAmountMinorPence }
    }

    @Test
    fun productsContainingKeywordLetters_areKept() {
        assertEquals(
            listOf("STILL WATER" to 50L, "OREGANO" to 100L, "REGULAR CRISPS" to 100L, "HOTEL CHOCOLAT" to 100L, "BIRTHDAY CARD" to 150L),
            itemsFrom("ASDA", "STILL WATER 0.50", "OREGANO 1.00", "REGULAR CRISPS 1.00", "HOTEL CHOCOLAT 1.00", "BIRTHDAY CARD 1.50", "TOTAL 5.00")
        )
    }

    @Test
    fun loyaltyHeaders_doNotEndTheItemList() {
        assertEquals(
            listOf("MILK" to 200L),
            itemsFrom("TESCO", "CLUBCARD STATEMENT", "MID 12345678", "MILK 2.00", "TOTAL 2.00")
        )
    }

    @Test
    fun priceWithTrailingVatLetter_isReadAndRemovedFromTheName() {
        assertEquals(listOf("MILK" to 200L, "BREAD" to 300L), itemsFrom("ASDA", "MILK 2.00 A", "BREAD 3.00 B", "TOTAL 5.00"))
    }

    /** ML Kit output from a real crumpled Asda receipt photographed on a phone. */
    @Test
    fun realPhonePhoto_readsEveryItemAndReconcilesWithTheTotal() {
        val rows = listOf(
            Triple("ASDA HAM", 913, "£4.00" to 899), Triple("MASH", 952, "£2.96" to 942),
            Triple("CHKN DIPPERS", 989, "£2.96" to 984), Triple("WEDGES", 1030, "12.40" to 1027),
            Triple("POT SMILES", 1069, "£2.40" to 1070), Triple("WEDGES", 1107, "22.40" to 1111),
            Triple("PIZZA", 1146, "£1.90" to 1152), Triple("BREAD", 1180, "£0.75" to 1190),
            Triple("LETTUCE", 1216, "£0.89" to 1230), Triple("CHEESE", 1253, "£4.45" to 1272),
            Triple("PIZZA", 1293, "e1.90" to 1317), Triple("CT SUB PIZZA", 1337, "S1.90" to 1361),
            Triple("4 FOR £5 FROZEN", 1383, "-£8.82" to 1404), Triple("MUFFINS", 1428, "20.22" to 1444),
            Triple("ASDA PLOIN", 1476, "£2.23" to 1482), Triple("ORALB PASTE", 1520, "S2.75" to 1520),
            Triple("MOUTHWASH", 1564, "£4.00" to 1568), Triple("TOTAL:", 1747, "C38.11" to 1748),
            Triple("TOTAL:", 1877, "£29.29" to 1878), Triple("CARD", 2095, "C29.29" to 2096)
        )
        val lines = mutableListOf(line("ASDA", 601, 508), line("TE. 36 TR. 7802", 993, 815), line("ST. 5834 OP. USER", 501, 830))
        rows.forEach { (description, y, price) ->
            lines += line(description, 470, y)
            lines += line(price.first, 1200, price.second)
        }
        lines += listOf(line("TOTAL SAVINGS: 8.82", 779, 1810), line("NO. ITEMS SOLD 16", 757, 2008))
        val sorted = lines.sortedBy { it.centerY }.mapIndexed { i, l -> l.copy(lineIndex = i) }

        val draft = ReceiptSpendParser.parseOcrResult(ReceiptOcrResult(sorted, sorted.joinToString("\n") { it.text } + "\n20/10/2026"), null)

        assertEquals(
            listOf(400L, 296L, 296L, 240L, 240L, 240L, 190L, 75L, 89L, 445L, 190L, 190L, 22L, 223L, 275L, 400L),
            draft.items.map { it.lineAmountMinorPence }
        )
        assertEquals("MUFFINS", draft.items[12].normalizedDescription)
        assertEquals(2929L, draft.totalMinorPence)
        assertEquals(3811L, draft.explicitGrossTotalMinorPence)
        assertEquals(882L, draft.discountMinorPence)
        assertEquals(true, draft.isReconciled)
    }

    @Test
    fun ambiguousMisreadPrices_areLeftForTheUserToReview() {
        val draft = ReceiptSpendParser.parseOcrResult(
            ReceiptOcrResult(
                listOf(
                    line("HOOVER BAGS", 100, 100), line("25.00", 800, 100),
                    line("MILK", 100, 140), line("23.00", 800, 140),
                    line("TOTAL", 100, 180), line("28.00", 800, 180)
                ).mapIndexed { i, l -> l.copy(lineIndex = i) },
                "ASDA 27/09/2026"
            ),
            null
        )

        assertEquals(listOf(2500L, 2300L), draft.items.map { it.lineAmountMinorPence })
        assertEquals(false, draft.isReconciled)
    }
}
