package com.example.chatgpt_mealplanner.core.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptDraftEditingTest {

    private fun parsed(vararg rows: String, fullTextExtra: String = "27/09/2026"): ReceiptSpendDraft {
        val lines = rows.mapIndexed { index, text ->
            val y = 100 + index * 40
            ReceiptOcrLine(text, 0.9f, index, left = 100, top = y, right = 900, bottom = y + 20, centerY = y + 10)
        }
        return ReceiptSpendParser.parseOcrResult(
            ReceiptOcrResult(lines, (rows.toList() + fullTextExtra).joinToString("\n")),
            null
        )
    }

    private fun item(description: String, pence: Long) = ReceiptSpendItemDraft(
        rawDescription = description,
        normalizedDescription = description,
        lineAmountMinorPence = pence,
        unitPriceMinorPence = pence
    )

    @Test
    fun enteringMissingDate_enablesSaveAndUpdatesFingerprint() {
        val draft = parsed("ASDA", "MILK 2.00", "TOTAL 2.00", fullTextExtra = "no date here")
        assertFalse(draft.canSave)
        assertTrue(draft.dateNeedsConfirmation)

        val edited = draft.withUserEdits(purchasedAt = "2026-09-27")

        assertTrue(edited.canSave)
        assertFalse(edited.dateNeedsConfirmation)
        assertEquals("2026-09-27", edited.purchasedAt)
        assertNull(edited.reviewWarning)
        assertNotEquals(draft.fingerprint, edited.fingerprint)
    }

    @Test
    fun enteringMissingTotal_enablesSaveAndKeepsReceiptSavingsInGross() {
        val draft = parsed("ASDA", "MILK 2.00")
        assertFalse(draft.canSave)

        val edited = draft.withUserEdits(paidTotalPence = 200L)

        assertTrue(edited.canSave)
        assertEquals(200L, edited.totalMinorPence)
        assertEquals(200L, edited.explicitGrossTotalMinorPence)
        assertTrue(edited.isReconciled)
        assertTrue(edited.summaryArithmeticValid)
    }

    @Test
    fun addingMissedItem_reconcilesReceipt() {
        val draft = parsed("ASDA", "MILK 2.00", "TOTAL 5.00")
        assertFalse(draft.isReconciled)
        assertEquals(300L, draft.discrepancyMinorPence)

        val edited = draft.withUserEdits(items = draft.items + item("BREAD", 300L))

        assertEquals(2, edited.itemCount)
        assertEquals(500L, edited.calculatedItemsSumMinorPence)
        assertEquals(500L, edited.recognisedGrossItemSpendMinorPence)
        assertEquals(0L, edited.discrepancyMinorPence)
        assertTrue(edited.isReconciled)
        assertNull(edited.reviewWarning)
    }

    @Test
    fun correctingMisreadPrice_clearsOverage() {
        val draft = parsed("ASDA", "MILK 8.00", "TOTAL 2.00")
        assertEquals(600L, draft.recognisedGrossOverageMinorPence)

        val corrected = draft.items.map { it.copy(lineAmountMinorPence = 200L, unitPriceMinorPence = 200L) }
        val edited = draft.withUserEdits(items = corrected)

        assertEquals(0L, edited.recognisedGrossOverageMinorPence)
        assertTrue(edited.isReconciled)
        assertNull(edited.reviewWarning)
    }

    @Test
    fun deletingPhantomItem_recalculatesSoTheDashboardTotalsStayCorrect() {
        val draft = parsed("ASDA", "MILK 2.00", "SMUDGED ROW 3.00", "TOTAL 2.00")
        assertEquals(2, draft.items.size)

        val edited = draft.withUserEdits(items = draft.items.take(1))

        assertEquals(1, edited.itemCount)
        assertEquals(200L, edited.recognisedGrossItemSpendMinorPence)
        assertEquals(0L, edited.recognisedGrossOverageMinorPence)
        assertTrue(edited.isReconciled)
    }

    @Test
    fun duplicateWarning_survivesItemEdits_butClearsWhenDateOrTotalChanges() {
        val duplicate = parsed("ASDA", "MILK 2.00", "TOTAL 2.00").copy(
            isProbableDuplicate = true,
            reviewWarning = DUPLICATE_RECEIPT_WARNING
        )

        val categoryEdit = duplicate.withUserEdits(items = duplicate.items.map { it.copy(category = "OTHER") })
        assertTrue(categoryEdit.isProbableDuplicate)
        assertEquals(DUPLICATE_RECEIPT_WARNING, categoryEdit.reviewWarning)
        assertEquals(duplicate.fingerprint, categoryEdit.fingerprint)

        val dateEdit = duplicate.withUserEdits(purchasedAt = "2026-09-28")
        assertFalse(dateEdit.isProbableDuplicate)
        assertNull(dateEdit.reviewWarning)
    }

    @Test
    fun editedFingerprint_matchesAFreshScanOfTheSameReceipt() {
        val scanned = parsed("ASDA", "MILK 2.00", "TOTAL 2.00", fullTextExtra = "no date here REF: 123456")
        val edited = scanned.withUserEdits(purchasedAt = "2026-09-27")
        val rescanned = parsed("ASDA", "MILK 2.00", "TOTAL 2.00", fullTextExtra = "27/09/2026 REF: 123456")

        assertEquals(rescanned.fingerprint, edited.fingerprint)
    }

    @Test
    fun receiptDates_mustBeRealCalendarDates() {
        fun read(text: String) = ReceiptSummaryParser.parseSummary(ReceiptOcrResult(emptyList(), text))

        assertEquals("2026-09-27", read("27/09/2026").purchasedAt)
        assertEquals("2026-09-27", read("27-09-26").purchasedAt)
        assertEquals("2000-01-01", read("01.01.2000").purchasedAt)
        assertEquals("2024-02-29", read("29/02/2024").purchasedAt)

        listOf("31/02/2026", "00/09/2026", "99/99/2026", "29/02/2026").forEach { invalid ->
            val summary = read(invalid)
            assertNull(invalid, summary.purchasedAt)
            assertTrue(invalid, summary.dateNeedsConfirmation)
        }
    }

    @Test
    fun invalidDateIsSkippedInFavourOfALaterValidOne() {
        val summary = ReceiptSummaryParser.parseSummary(ReceiptOcrResult(emptyList(), "99/99/2026 then 27/09/2026"))
        assertEquals("2026-09-27", summary.purchasedAt)
    }

    @Test
    fun typedAmounts_parseExactlyWithoutFloatingPointRounding() {
        assertEquals(1999L, ReceiptMoneyInput.parsePositivePence("19.99"))
        assertEquals(374L, ReceiptMoneyInput.parsePositivePence(" £3.74 "))
        assertEquals(370L, ReceiptMoneyInput.parsePositivePence("3,7"))
        assertEquals(500L, ReceiptMoneyInput.parsePositivePence("5"))
        assertEquals("19.99", ReceiptMoneyInput.format(1999L))

        listOf("", "abc", "-1.00", "0", "0.00", "1.234", "1.2.3", "£").forEach { invalid ->
            assertNull(invalid, ReceiptMoneyInput.parsePositivePence(invalid))
        }
    }
}
