package com.example.chatgpt_mealplanner.core.receipt

import com.example.chatgpt_mealplanner.core.ai.packet.ReceiptPhotoReader
import com.example.chatgpt_mealplanner.core.ai.provider.ReadReceipt
import com.example.chatgpt_mealplanner.core.ai.provider.ReadReceiptLine
import com.example.chatgpt_mealplanner.core.ai.provider.ReceiptReadingParsing
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptReadingDraftTest {
    private fun line(name: String, amount: String, cat: String = "FOOD", qty: Int = 1) = ReadReceiptLine(name.uppercase(), name, cat, qty, amount)

    @Test
    fun amountsInEveryStyle() {
        assertEquals(374L, ReceiptReadingDraft.minorUnits("3.74", "GBP"))
        assertEquals(374L, ReceiptReadingDraft.minorUnits("£3,74", "GBP"))
        assertEquals(123456L, ReceiptReadingDraft.minorUnits("1.234,56", "EUR"))
        assertEquals(123456L, ReceiptReadingDraft.minorUnits("1,234.56", "USD"))
        assertEquals(1234L, ReceiptReadingDraft.minorUnits("1,234", "JPY"))
        assertEquals(130L, ReceiptReadingDraft.minorUnits("130", "JPY"))
        assertEquals(-50L, ReceiptReadingDraft.minorUnits("-0.50", "EUR"))
        assertNull(ReceiptReadingDraft.minorUnits("n/a", "GBP"))
        assertNull(ReceiptReadingDraft.minorUnits(null, "GBP"))
    }

    @Test
    fun aGermanReceiptBecomesAReconciledEuroDraft() {
        val draft = ReceiptReadingDraft.toDraft(
            ReadReceipt("Lidl", "EUR", "2026-11-17", listOf(line("Vollkornbrot", "1,29"), line("Frische Milch", "0.99", qty = 1), line("Butter", "1.89")), listOf("0,50"), "4,17", "3,67"),
            "file://x", Locale.UK
        )
        assertEquals("EUR", draft.currencyCode)
        assertEquals(listOf(129L, 99L, 189L), draft.items.map { it.lineAmountMinorPence })
        assertEquals("Vollkornbrot", draft.items[0].normalizedDescription)
        assertEquals(367L, draft.totalMinorPence)
        assertEquals(50L, draft.discountMinorPence)
        assertEquals("2026-11-17", draft.purchasedAt)
        assertTrue(draft.reviewWarning ?: "ok", draft.isReconciled && draft.summaryArithmeticValid && draft.canSave)
        assertEquals("LIDL", draft.retailerId)
    }

    @Test
    fun yenAmountsAreWholeUnits() {
        val draft = ReceiptReadingDraft.toDraft(ReadReceipt("FamilyMart", "JPY", "2026-11-17", listOf(line("Onigiri", "130"), line("Green tea", "150")), emptyList(), null, "280"), null)
        assertEquals(listOf(130L, 150L), draft.items.map { it.lineAmountMinorPence })
        assertEquals(280L, draft.totalMinorPence)
        assertTrue(draft.isReconciled)
    }

    @Test
    fun knownChainsKeepTheirShortId_andBadDataIsHandled() {
        assertEquals("ASDA", ReceiptReadingDraft.retailerId("Asda Superstore"))
        assertEquals("TRADER_JOE_S", ReceiptReadingDraft.retailerId("Trader Joe's"))
        val draft = ReceiptReadingDraft.toDraft(ReadReceipt("X", "ZZZ", "not-a-date", listOf(line("Thing", "abc"), line("Free", "0.00"), line("Real", "2.00")), emptyList(), null, null), null, Locale.UK)
        assertEquals("GBP", draft.currencyCode)
        assertEquals(1, draft.items.size)
        assertNull(draft.purchasedAt)
        assertTrue(draft.dateNeedsConfirmation)
        assertFalse(draft.canSave)
    }

    @Test
    fun aTotalThatDoesNotMatchTheItemsIsFlaggedNotInvented() {
        val draft = ReceiptReadingDraft.toDraft(ReadReceipt("Tesco", "GBP", "2026-11-17", listOf(line("Bread", "1.10")), emptyList(), null, "2.65"), null)
        assertEquals(265L, draft.totalMinorPence)
        assertFalse(draft.isReconciled)
        assertNotNull(draft.reviewWarning)
    }

    @Test
    fun toolReplyIsParsedAndUnknownCategoriesBecomeUncategorised() {
        val read = ReceiptReadingParsing.parse("""{"retailer":"Lidl","currency":"eur","date":"2026-11-17","items":[{"printedText":"BROT","name":"Brot","category":"FOOD","quantity":2,"amount":"2.58"},{"printedText":"X","name":"X","category":"WEIRD","quantity":1,"amount":"1.00"},{"name":"no amount"}],"discounts":["0.50"],"totalPaid":"3.08"}""")!!
        assertEquals("EUR", read.currency)
        assertEquals(2, read.items.size)
        assertEquals(2, read.items[0].quantity)
        assertEquals("UNCATEGORISED", read.items[1].category)
        assertEquals(listOf("0.50"), read.discounts)
        assertNull(ReceiptReadingParsing.parse("not json"))
    }

    @Test
    fun tallReceiptsAreCutIntoOverlappingParts() {
        assertEquals(listOf(0 to 1500), ReceiptPhotoReader.tileRanges(1500, 1000))
        val tiles = ReceiptPhotoReader.tileRanges(6000, 1568)
        assertTrue(tiles.size in 2..4)
        assertEquals(0, tiles.first().first)
        assertEquals(6000, tiles.last().second)
        tiles.zipWithNext().forEach { (a, b) -> assertTrue("parts overlap", b.first < a.second) }
    }
}
