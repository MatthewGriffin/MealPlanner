package com.example.chatgpt_mealplanner.core.receipt

import com.example.chatgpt_mealplanner.core.ai.provider.ReceiptReadingParsing
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Claude should return for the photos in example_receipts/, as the tool call's JSON. These are recorded expectations,
 * not live calls: they keep the parsing, money conversion and review rules honest on real receipts.
 */
class ExampleReceiptReadingTest {

    private val morrisons = """
        {"retailer":"Morrisons Daily","currency":"GBP","date":"2026-11-17",
         "items":[
          {"printedText":"64006761 Hovis Soft Whit","name":"Hovis Soft White Bread","category":"FOOD","quantity":1,"amount":"1.65"},
          {"printedText":"81008025 Four Loko Pink","name":"Four Loko Pink","category":"DRINKS","quantity":1,"amount":"4.00"},
          {"printedText":"42004413 Lucozade Alert","name":"Lucozade Alert","category":"DRINKS","quantity":1,"amount":"2.00"}],
         "discounts":["0.99"],"subtotal":"7.65","totalPaid":"6.66"}
    """.trimIndent()

    private val asda = """
        {"retailer":"ASDA","currency":"GBP","date":"2026-10-01",
         "items":[
          {"printedText":"ASDA HAM","name":"Asda Ham","category":"FOOD","quantity":1,"amount":"4.00"},
          {"printedText":"MASH","name":"Mash","category":"FOOD","quantity":1,"amount":"2.96"},
          {"printedText":"CHKN DIPPERS","name":"Chicken Dippers","category":"FOOD","quantity":1,"amount":"2.96"},
          {"printedText":"WEDGES","name":"Wedges","category":"FOOD","quantity":1,"amount":"2.40"},
          {"printedText":"POT SMILES","name":"Potato Smiles","category":"FOOD","quantity":1,"amount":"2.40"},
          {"printedText":"WEDGES","name":"Wedges","category":"FOOD","quantity":1,"amount":"2.40"},
          {"printedText":"PIZZA","name":"Pizza","category":"FOOD","quantity":1,"amount":"1.90"},
          {"printedText":"BREAD","name":"Bread","category":"FOOD","quantity":1,"amount":"0.75"},
          {"printedText":"LETTUCE","name":"Lettuce","category":"FOOD","quantity":1,"amount":"0.89"},
          {"printedText":"CHEESE","name":"Cheese","category":"FOOD","quantity":1,"amount":"4.45"},
          {"printedText":"PIZZA","name":"Pizza","category":"FOOD","quantity":1,"amount":"1.90"},
          {"printedText":"CT SUB PIZZA","name":"Pizza","category":"FOOD","quantity":1,"amount":"1.90"},
          {"printedText":"MUFFINS","name":"Muffins","category":"FOOD","quantity":1,"amount":"0.22"},
          {"printedText":"ASDA PLOIN","name":"Asda Plain","category":"FOOD","quantity":1,"amount":"2.23"},
          {"printedText":"ORALB PASTE","name":"Oral-B Toothpaste","category":"PERSONAL_CARE","quantity":1,"amount":"2.75"},
          {"printedText":"MOUTHWASH","name":"Mouthwash","category":"PERSONAL_CARE","quantity":1,"amount":"4.00"}],
         "discounts":["8.82"],"subtotal":"38.11","totalPaid":"29.29"}
    """.trimIndent()

    private fun draft(json: String) =
        ReceiptReadingDraft.toDraft(ReceiptReadingParsing.parse(json)!!, "file://example", Locale.UK)

    @Test
    fun morrisonsReceipt_markdownIsASavingNotAnItem_andItAddsUp() {
        val d = draft(morrisons)
        assertEquals("GBP", d.currencyCode)
        assertEquals("2026-11-17", d.purchasedAt)
        assertEquals(listOf("Hovis Soft White Bread", "Four Loko Pink", "Lucozade Alert"), d.items.map { it.normalizedDescription })
        assertEquals(listOf(165L, 400L, 200L), d.items.map { it.lineAmountMinorPence })
        assertEquals(99L, d.discountMinorPence)
        assertEquals(666L, d.totalMinorPence)
        assertEquals(listOf("FOOD", "DRINKS", "DRINKS"), d.items.map { it.category })
        assertTrue(d.reviewWarning ?: "ok", d.isReconciled && d.summaryArithmeticValid && d.canSave)
        assertTrue(d.items.none { it.normalizedDescription.contains("Mastercard", true) || it.normalizedDescription.contains("Change", true) })
    }

    @Test
    fun asdaReceipt_sixteenItemsAndTheFrozenDealAddUpTo2929() {
        val d = draft(asda)
        assertEquals("ASDA", d.retailerId)
        assertEquals("2026-10-01", d.purchasedAt)
        assertEquals(16, d.items.size)
        assertEquals(3811L, d.calculatedItemsSumMinorPence)
        assertEquals(882L, d.discountMinorPence)
        assertEquals(2929L, d.totalMinorPence)
        assertEquals(listOf("PERSONAL_CARE", "PERSONAL_CARE"), d.items.map { it.category }.filter { it == "PERSONAL_CARE" })
        assertTrue(d.reviewWarning ?: "ok", d.isReconciled && d.summaryArithmeticValid && d.canSave)
    }

    @Test
    fun theSameReceiptReadTwiceHasTheSameFingerprint_soADuplicateIsCaught() {
        assertEquals(draft(asda).fingerprint, draft(asda).fingerprint)
        assertTrue(draft(asda).fingerprint != draft(morrisons).fingerprint)
    }
}
