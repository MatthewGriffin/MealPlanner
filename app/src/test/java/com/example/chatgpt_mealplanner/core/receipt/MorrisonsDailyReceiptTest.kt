package com.example.chatgpt_mealplanner.core.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorrisonsDailyReceiptTest {

    @Test
    fun priceOverrideNoteMarkdownAndCardTenderAreNotItems_andTheReceiptAddsUp() {
        val draft = ReceiptSpendParser.parseOcrResult(MorrisonsDailyReceiptOcrFixture.create(), "morrisons.jpg")

        // Three products: bread 1.65, Four Loko 4.00, Lucozade 2.00.
        assertEquals(listOf(165L, 400L, 200L), draft.items.map { it.lineAmountMinorPence })
        assertTrue(draft.items.none { it.normalizedDescription.contains("OVERRIDE") || it.normalizedDescription.contains("MASTERCARD") })
        // Till codes are not part of the names.
        assertTrue(draft.items.none { it.normalizedDescription.first().isDigit() })

        // The manager markdown of 0.99 is a saving; £7.65 of products less that is the £6.66 paid.
        assertEquals(listOf("FOOD", "DRINKS", "DRINKS"), draft.items.map { it.category })

        assertEquals(99L, draft.discountMinorPence)
        assertEquals(666L, draft.totalMinorPence)
        assertEquals(765L, draft.calculatedItemsSumMinorPence)
        assertTrue(draft.isReconciled)
    }

    @Test
    fun aMisreadCardNameStillCannotBecomeAnItemBecauseTenderedMarksThePaymentArea() {
        val real = MorrisonsDailyReceiptOcrFixture.create()
        val misread = real.copy(lines = real.lines.map {
            if (it.text == "Mastercard Tendered") it.copy(text = "Mastercarcl Tendered", elements = it.elements.map { e -> if (e.text == "Mastercard") e.copy(text = "Mastercarcl") else e }) else it
        })
        val draft = ReceiptSpendParser.parseOcrResult(misread, "morrisons.jpg")
        assertEquals(listOf(165L, 400L, 200L), draft.items.map { it.lineAmountMinorPence })
        assertEquals(666L, draft.totalMinorPence)
    }
}
