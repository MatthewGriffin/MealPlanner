package com.example.chatgpt_mealplanner.core.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AsdaRealReceiptFixtureTest {

    @Test
    fun percentageTokens_mustNeverParseAsMoney() {
        assertNull(ReceiptMoneyParser.parseAmountPence("0.00%"))
        assertNull(ReceiptMoneyParser.parseAmountPence("20.00%"))
        assertNull(ReceiptMoneyParser.parseAmountPence("5%"))
        assertNull(ReceiptMoneyParser.parseAmountPence("12.5%"))

        assertEquals(2000L, ReceiptMoneyParser.parseAmountPence("£20.00"))
        assertEquals(374L, ReceiptMoneyParser.parseAmountPence("£3.74"))
    }

    @Test
    fun singleFallbackElement_wedgesCorruptedCode_isRejected() {
        // Line where OCR produced only 1 whole-line element: "WEDGESON03HORE J3GH 92.40"
        val line = ReceiptOcrLine(
            text = "WEDGESON03HORE J3GH 92.40",
            confidence = 0.9f,
            lineIndex = 0,
            left = 10, top = 10, right = 300, bottom = 20, centerY = 15,
            elements = listOf(
                ReceiptOcrElement("WEDGESON03HORE J3GH 92.40", 10, 10, 300, 20, 15) // Single fallback element
            )
        )

        val ocrResult = ReceiptOcrResult(lines = listOf(line), fullText = "WEDGESON03HORE J3GH 92.40")
        val draft = ReceiptSpendParser.parseOcrResult(ocrResult, null)

        // Single fallback elements without separately detected price elements must be REJECTED!
        assertEquals(0, draft.items.size)
    }

    @Test
    fun separateElements_validProductLine_isAccepted() {
        // Line with separate OCR elements: [PIZZA] [3.74]
        val line = ReceiptOcrLine(
            text = "PIZZA 3.74",
            confidence = 0.95f,
            lineIndex = 0,
            left = 10, top = 10, right = 300, bottom = 20, centerY = 15,
            elements = listOf(
                ReceiptOcrElement("PIZZA", 10, 10, 100, 20, 15),
                ReceiptOcrElement("3.74", 250, 10, 300, 20, 15)
            )
        )

        val ocrResult = ReceiptOcrResult(lines = listOf(line), fullText = "PIZZA 3.74")
        val draft = ReceiptSpendParser.parseOcrResult(ocrResult, null)

        assertEquals(1, draft.items.size)
        assertEquals("PIZZA", draft.items.first().normalizedDescription)
        assertEquals(374L, draft.items.first().lineAmountMinorPence)
    }

    @Test
    fun splitSummaryLines_totalAndAmountOnSeparateLines_isParsed() {
        val ocrResult = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0),
                ReceiptOcrLine("TOTAL", 0.9f, 1),
                ReceiptOcrLine("38.88", 0.9f, 2)
            ),
            fullText = "ASDA 27/09/2026 TOTAL 38.88"
        )

        val summary = ReceiptSummaryParser.parseSummary(ocrResult)
        assertEquals(3888L, summary.finalPaidTotalMinorPence)
    }

    @Test
    fun splitPaymentCardLine_cardAndAmountOnSeparateLines_isParsed() {
        val ocrResult = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0),
                ReceiptOcrLine("CARD", 0.9f, 1),
                ReceiptOcrLine("38.88", 0.9f, 2)
            ),
            fullText = "ASDA 27/09/2026 CARD 38.88"
        )

        val summary = ReceiptSummaryParser.parseSummary(ocrResult)
        assertEquals(3888L, summary.cardPaymentMinorPence)
        assertEquals(3888L, summary.finalPaidTotalMinorPence)
    }

    @Test
    fun missingSummaryTotal_setsFinalPaidNull_andBlocksSave() {
        val ocrResult = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0), ReceiptOcrElement("27/09/2026", 0,0,0,0,0))),
                ReceiptOcrLine("PIZZA 3.74", 0.9f, 1, elements = listOf(ReceiptOcrElement("PIZZA", 0,0,0,0,0), ReceiptOcrElement("3.74", 0,0,0,0,0)))
            ),
            fullText = "ASDA 27/09/2026 PIZZA 3.74" // NO TOTAL LINES
        )

        val draft = ReceiptSpendParser.parseOcrResult(ocrResult, null)

        assertNull(draft.explicitFinalPaidMinorPence)
        assertFalse(draft.canSave) // Save blocked!
        assertTrue(draft.reviewWarning!!.contains("couldn't detect the amount paid"))
    }

    @Test
    fun manualTotalCorrection_recomputesAllFields_andEnablesSave() {
        val ocrResult = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0), ReceiptOcrElement("27/09/2026", 0,0,0,0,0))),
                ReceiptOcrLine("PIZZA 3.74", 0.9f, 1, elements = listOf(ReceiptOcrElement("PIZZA", 0,0,0,0,0), ReceiptOcrElement("3.74", 0,0,0,0,0)))
            ),
            fullText = "ASDA 27/09/2026 PIZZA 3.74"
        )

        val unparsedDraft = ReceiptSpendParser.parseOcrResult(ocrResult, null)
        assertFalse(unparsedDraft.canSave)

        // User manually corrects paid total to £3.74 (374p)
        val correctedDraft = unparsedDraft.recomputeWithPaidTotal(374L)

        assertEquals(374L, correctedDraft.totalMinorPence)
        assertEquals(374L, correctedDraft.explicitFinalPaidMinorPence)
        assertTrue(correctedDraft.userConfirmedSummary)
        assertTrue(correctedDraft.canSave) // Save enabled!
    }

    @Test
    fun savingsMagnitudeInvariants_usesPositivePenceMagnitude() {
        val draft = ReceiptSpendDraft(
            retailerId = "ASDA",
            purchasedAt = "2026-09-27",
            totalMinorPence = 3888L,
            explicitGrossTotalMinorPence = 4248L,
            explicitSavingsMinorPence = 360L,
            explicitFinalPaidMinorPence = 3888L,
            recognisedGrossItemSpendMinorPence = 4248L,
            unrecognisedGrossSpendMinorPence = 0L,
            recognisedGrossOverageMinorPence = 0L,
            calculatedItemsSumMinorPence = 4248L,
            discountMinorPence = 360L,
            itemCount = 12,
            expectedItemCount = 12,
            discrepancyMinorPence = 0L,
            summaryArithmeticValid = true,
            isReconciled = true,
            needsReview = false,
            canSave = true,
            reviewWarning = null,
            fingerprint = "test_fp",
            sourceImagePath = null,
            items = emptyList()
        )

        assertEquals(3888L, draft.calculatedItemsSumMinorPence - draft.discountMinorPence)
    }

    @Test
    fun parseOcrResult_asdaRealReceiptFixture_parsesExactly12Items_andExcludesTaxSummary() {
        val ocrResult = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA SUPERSTORE", 0.95f, 0, top = 10, centerY = 10, elements = listOf(ReceiptOcrElement("ASDA", 10, 10, 50, 20, 10), ReceiptOcrElement("SUPERSTORE", 60, 10, 150, 20, 10))),
                ReceiptOcrLine("PIZZA £3.74", 0.95f, 1, top = 20, centerY = 20, elements = listOf(ReceiptOcrElement("PIZZA", 10, 20, 100, 30, 20), ReceiptOcrElement("£3.74", 250, 20, 300, 30, 20))),
                ReceiptOcrLine("PIZZA £3.74", 0.95f, 2, top = 30, centerY = 30, elements = listOf(ReceiptOcrElement("PIZZA", 10, 30, 100, 40, 30), ReceiptOcrElement("£3.74", 250, 30, 300, 40, 30))),
                ReceiptOcrLine("SNACKS £1.65", 0.95f, 3, top = 40, centerY = 40, elements = listOf(ReceiptOcrElement("SNACKS", 10, 40, 100, 50, 40), ReceiptOcrElement("£1.65", 250, 40, 300, 50, 40))),
                ReceiptOcrLine("CLEANING £12.00", 0.95f, 4, top = 50, centerY = 50, elements = listOf(ReceiptOcrElement("CLEANING", 10, 50, 100, 60, 50), ReceiptOcrElement("£12.00", 250, 50, 300, 60, 50))),
                ReceiptOcrLine("WEDGES £2.40", 0.95f, 5, top = 60, centerY = 60, elements = listOf(ReceiptOcrElement("WEDGES", 10, 60, 100, 70, 60), ReceiptOcrElement("£2.40", 250, 60, 300, 70, 60))),
                ReceiptOcrLine("PIZZA £1.90", 0.95f, 6, top = 70, centerY = 70, elements = listOf(ReceiptOcrElement("PIZZA", 10, 70, 100, 80, 70), ReceiptOcrElement("£1.90", 250, 70, 300, 80, 70))),
                ReceiptOcrLine("PIZZA £1.90", 0.95f, 7, top = 80, centerY = 80, elements = listOf(ReceiptOcrElement("PIZZA", 10, 80, 100, 90, 80), ReceiptOcrElement("£1.90", 250, 80, 300, 90, 80))),
                ReceiptOcrLine("DIPS £1.50", 0.95f, 8, top = 90, centerY = 90, elements = listOf(ReceiptOcrElement("DIPS", 10, 90, 100, 100, 90), ReceiptOcrElement("£1.50", 250, 90, 300, 100, 90))),
                ReceiptOcrLine("ASDA DTE £1.50", 0.95f, 9, top = 100, centerY = 100, elements = listOf(ReceiptOcrElement("ASDA DTE", 10, 100, 100, 110, 100), ReceiptOcrElement("£1.50", 250, 100, 300, 110, 100))),
                ReceiptOcrLine("FINISH £5.75", 0.95f, 10, top = 110, centerY = 110, elements = listOf(ReceiptOcrElement("FINISH", 10, 110, 100, 120, 110), ReceiptOcrElement("£5.75", 250, 110, 300, 120, 110))),
                ReceiptOcrLine("WEDGES £2.40", 0.95f, 11, top = 120, centerY = 120, elements = listOf(ReceiptOcrElement("WEDGES", 10, 120, 100, 130, 120), ReceiptOcrElement("£2.40", 250, 120, 300, 130, 120))),
                ReceiptOcrLine("4 FOR £5 FROZEN -£3.60", 0.95f, 12, top = 130, centerY = 130, elements = listOf(ReceiptOcrElement("4 FOR £5 FROZEN", 10, 130, 100, 140, 130), ReceiptOcrElement("-£3.60", 250, 130, 300, 140, 130))),
                ReceiptOcrLine("ASDA HAM £4.00", 0.95f, 13, top = 140, centerY = 140, elements = listOf(ReceiptOcrElement("ASDA HAM", 10, 140, 100, 150, 140), ReceiptOcrElement("£4.00", 250, 140, 300, 150, 140))),
                ReceiptOcrLine("TOTAL £42.48", 0.95f, 14, top = 150, centerY = 150, elements = listOf(ReceiptOcrElement("TOTAL", 10, 150, 100, 160, 150), ReceiptOcrElement("£42.48", 250, 150, 300, 160, 150))),
                ReceiptOcrLine("TOTAL SAVINGS -£3.60", 0.95f, 15, top = 160, centerY = 160, elements = listOf(ReceiptOcrElement("TOTAL SAVINGS", 10, 160, 100, 170, 160), ReceiptOcrElement("-£3.60", 250, 160, 300, 170, 160))),
                ReceiptOcrLine("TOTAL £38.88", 0.95f, 16, top = 170, centerY = 170, elements = listOf(ReceiptOcrElement("TOTAL", 10, 170, 100, 180, 170), ReceiptOcrElement("£38.88", 250, 170, 300, 180, 170))),
                ReceiptOcrLine("NO. ITEMS SOLD 12", 0.95f, 17, top = 180, centerY = 180, elements = listOf(ReceiptOcrElement("NO. ITEMS SOLD", 10, 180, 100, 190, 180), ReceiptOcrElement("12", 250, 180, 300, 190, 180))),
                ReceiptOcrLine("CARD £38.88", 0.95f, 18, top = 190, centerY = 190, elements = listOf(ReceiptOcrElement("CARD", 10, 190, 100, 200, 190), ReceiptOcrElement("£38.88", 250, 190, 300, 200, 190))),
                ReceiptOcrLine("TAX SUMMARY", 0.95f, 19, top = 1700, centerY = 1710, elements = listOf(ReceiptOcrElement("TAX SUMMARY", 10, 1700, 100, 1710, 1710))),
                ReceiptOcrLine("0.00%", 0.95f, 20, top = 1720, centerY = 1730, elements = listOf(ReceiptOcrElement("0.00%", 10, 1720, 100, 1730, 1725))),
                ReceiptOcrLine("20.00%", 0.95f, 21, top = 1740, centerY = 1750, elements = listOf(ReceiptOcrElement("20.00%", 10, 1740, 100, 1750, 1745))),
                ReceiptOcrLine("21.13", 0.95f, 22, top = 1760, centerY = 1770, elements = listOf(ReceiptOcrElement("21.13", 10, 1760, 100, 1770, 1765))),
                ReceiptOcrLine("14.79", 0.95f, 23, top = 1780, centerY = 1790, elements = listOf(ReceiptOcrElement("14.79", 10, 1780, 100, 1790, 1785))),
                ReceiptOcrLine("2.96", 0.95f, 24, top = 1800, centerY = 1810, elements = listOf(ReceiptOcrElement("2.96", 10, 1800, 100, 1810, 1805)))
            ),
            fullText = "ASDA RECEIPT 27/09/2026"
        )

        val draft = ReceiptSpendParser.parseOcrResult(ocrResult, "asda_test.jpg")

        assertEquals("ASDA", draft.retailerId)
        assertEquals(12, draft.itemCount)
        assertEquals(12, draft.expectedItemCount)
        assertEquals(3888L, draft.totalMinorPence) // £38.88 final paid
        assertEquals(360L, draft.discountMinorPence) // £3.60 total savings
        assertEquals(4248L, draft.calculatedItemsSumMinorPence) // £42.48 gross items sum

        val itemAmounts = draft.items.map { it.lineAmountMinorPence }
        val expectedAmounts = listOf(374L, 374L, 165L, 1200L, 240L, 190L, 190L, 150L, 150L, 575L, 240L, 400L)
        assertEquals(expectedAmounts, itemAmounts)

        // Verify HARD EXCLUSION of Tax Summary values (0.00%, 20.00%, 21.13, 14.79, 2.96) and Card Payment (£38.88)
        val allNormDescs = draft.items.map { it.normalizedDescription }
        assertTrue(allNormDescs.none { it.contains("0.00") || it.contains("20.00") || it.contains("21.13") || it.contains("14.79") || it.contains("2.96") || it.contains("CARD") || it.contains("TAX") })
    }
}
