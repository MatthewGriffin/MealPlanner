package com.example.chatgpt_mealplanner.core.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AsdaPhysicalReceiptOcrFixtureTest {

    @Test
    fun realAsdaDevice79LineOcr_reconstructsAll12ExactItemsAndReconciles() {
        val ocrResult = AsdaPhysicalReceiptOcrFixture.createRealDevice79LineOcrResult()
        val draft = ReceiptSpendParser.parseOcrResult(ocrResult, "real_asda_v8.jpg")

        assertEquals(12, draft.items.size)
        assertEquals(4248L, draft.calculatedItemsSumMinorPence) // £42.48 gross items sum
        assertEquals(360L, draft.discountMinorPence)           // £3.60 savings
        assertEquals(3888L, draft.totalMinorPence)             // £38.88 final paid
        assertEquals(0L, draft.recognisedGrossOverageMinorPence)
        assertTrue(draft.isReconciled)

        // Verify ASDA HAM is present at 400p and ADNO NOLEMAOINIE is NOT present
        val descs = draft.items.map { it.normalizedDescription }
        assertTrue(descs.contains("ASDA HAM"))
        assertTrue(descs.none { it.contains("ADNO") || it.contains("NOLEMAOINIE") || it.contains("HORE") || it.contains("J3GH") })

        // Verify exact item prices list
        val itemPrices = draft.items.map { it.lineAmountMinorPence }
        val expectedPrices = listOf(374L, 374L, 165L, 1200L, 240L, 190L, 190L, 150L, 150L, 575L, 400L, 240L)
        assertEquals(expectedPrices, itemPrices)
    }

    @Test
    fun legitimate9240Product_isNeverReducedTo240ByReconciliation() {
        val ocrResult = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0))),
                ReceiptOcrLine("HIGH END ITEM 92.40", 0.9f, 1, elements = listOf(ReceiptOcrElement("HIGH END ITEM", 10,10,100,20,15), ReceiptOcrElement("92.40", 250,10,300,20,15))),
                ReceiptOcrLine("TOTAL £92.40", 0.9f, 2, elements = listOf(ReceiptOcrElement("TOTAL", 10,10,100,20,15), ReceiptOcrElement("£92.40", 250,10,300,20,15)))
            ),
            fullText = "ASDA 27/09/2026 HIGH END ITEM 92.40 TOTAL £92.40"
        )

        val draft = ReceiptSpendParser.parseOcrResult(ocrResult, null)

        assertEquals(1, draft.items.size)
        assertEquals(9240L, draft.items.first().lineAmountMinorPence) // £92.40 preserved!
        assertEquals(9240L, draft.calculatedItemsSumMinorPence)
        assertEquals(0L, draft.recognisedGrossOverageMinorPence)
        assertTrue(draft.isReconciled)
    }

    @Test
    fun corrupted9240Token_withoutIndependentRecoveryEvidence_isRejected() {
        val line = ReceiptOcrLine(
            text = "WEDGES0N03HORe J3gH 92.40",
            confidence = 0.95f, // High confidence clean line without corruption signal
            lineIndex = 0,
            left = 10, top = 10, right = 300, bottom = 20, centerY = 15,
            elements = listOf(
                ReceiptOcrElement("WEDGES0N03HORe", 10, 10, 150, 20, 15),
                ReceiptOcrElement("J3gH", 160, 10, 200, 20, 15),
                ReceiptOcrElement("92.40", 250, 10, 300, 20, 15)
            )
        )

        val ocrResult = ReceiptOcrResult(lines = listOf(line), fullText = "WEDGES0N03HORe J3gH 92.40")
        val draft = ReceiptSpendParser.parseOcrResult(ocrResult, null)

        assertEquals(0, draft.items.size) // Rejected, no fabricated 240p item
    }

    @Test
    fun receiptTotalsAlone_cannotTransform9240To240() {
        val ocrResult = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0))),
                ReceiptOcrLine("WEDGES0N03HORe J3gH 92.40", 0.95f, 1, elements = listOf(ReceiptOcrElement("WEDGES0N03HORe", 10,10,100,20,15), ReceiptOcrElement("92.40", 250,10,300,20,15))),
                ReceiptOcrLine("TOTAL £2.40", 0.9f, 2, elements = listOf(ReceiptOcrElement("TOTAL", 10,10,100,20,15), ReceiptOcrElement("£2.40", 250,10,300,20,15)))
            ),
            fullText = "ASDA 27/09/2026 WEDGES0N03HORe J3gH 92.40 TOTAL £2.40"
        )

        val draft = ReceiptSpendParser.parseOcrResult(ocrResult, null)

        // Receipt total alone (£2.40) CANNOT transform 92.40 to 2.40 on a rejected till code line!
        assertEquals(0, draft.items.size)
    }

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
    fun separateElements_validProductLine_isAccepted() {
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
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0))),
                ReceiptOcrLine("PIZZA 3.74", 0.9f, 1, elements = listOf(ReceiptOcrElement("PIZZA", 0,0,0,0,0), ReceiptOcrElement("3.74", 0,0,0,0,0)))
            ),
            fullText = "ASDA 27/09/2026 PIZZA 3.74"
        )

        val draft = ReceiptSpendParser.parseOcrResult(ocrResult, null)

        assertNull(draft.explicitFinalPaidMinorPence)
        assertFalse(draft.canSave)
        assertTrue(draft.reviewWarning!!.contains("couldn't detect the amount paid"))
    }

    @Test
    fun manualTotalCorrection_recomputesAllFields_andEnablesSave() {
        val ocrResult = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0))),
                ReceiptOcrLine("PIZZA 3.74", 0.9f, 1, elements = listOf(ReceiptOcrElement("PIZZA", 0,0,0,0,0), ReceiptOcrElement("3.74", 0,0,0,0,0)))
            ),
            fullText = "ASDA 27/09/2026 PIZZA 3.74"
        )

        val unparsedDraft = ReceiptSpendParser.parseOcrResult(ocrResult, null)
        assertFalse(unparsedDraft.canSave)

        val correctedDraft = unparsedDraft.recomputeWithPaidTotal(374L)

        assertEquals(374L, correctedDraft.totalMinorPence)
        assertEquals(374L, correctedDraft.explicitFinalPaidMinorPence)
        assertTrue(correctedDraft.userConfirmedSummary)
        assertTrue(correctedDraft.canSave)
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
}
