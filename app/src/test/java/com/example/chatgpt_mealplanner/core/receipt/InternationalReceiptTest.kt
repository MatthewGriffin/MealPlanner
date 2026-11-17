package com.example.chatgpt_mealplanner.core.receipt

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone-side fallback reader on receipts from other countries (rows are laid out like real OCR: product left, price right). */
class InternationalReceiptTest {

    private fun receipt(vararg rows: String): ReceiptOcrResult {
        val lines = rows.mapIndexed { index, text ->
            val y = 100 + index * 40
            val words = text.split(' ').filter { it.isNotBlank() }
            val elements = words.mapIndexed { i, w ->
                val isLast = i == words.lastIndex && words.size > 1 && w.any { it.isDigit() }
                val left = if (isLast) 800 else 100 + i * 120
                ReceiptOcrElement(w, left, y, left + 90, y + 20, y + 10)
            }
            ReceiptOcrLine(text, 0.9f, index, left = 100, top = y, right = 900, bottom = y + 20, centerY = y + 10, elements = elements)
        }
        return ReceiptOcrResult(lines, rows.joinToString("\n"))
    }

    @Test
    fun germanEuroReceipt_isReadInEuros() {
        val draft = ReceiptSpendParser.parseOcrResult(
            receipt(
                "LIDL", "Musterstrasse 1 12345 Berlin", "17.11.2026 14:22",
                "Brot Vollkorn 1,29", "Milch Frisch 0,99", "Butter 1,89", "Rabatt -0,50",
                "SUMME EUR 3,67", "Gegeben 10,00", "Rueckgeld 6,33"
            ),
            null, Locale.GERMANY
        )
        assertEquals("EUR", draft.currencyCode)
        assertEquals(listOf(129L, 99L, 189L), draft.items.map { it.lineAmountMinorPence })
        assertEquals(50L, draft.discountMinorPence)
        assertEquals(367L, draft.totalMinorPence)
        assertEquals("2026-11-17", draft.purchasedAt)
        assertTrue(draft.reviewWarning ?: "reconciled", draft.isReconciled)
        assertEquals(listOf("FOOD"), draft.items.map { it.category }.distinct())
    }

    @Test
    fun frenchReceipt_withTotalAPayerAndCarteBancaire() {
        val draft = ReceiptSpendParser.parseOcrResult(
            receipt(
                "CARREFOUR MARKET", "17/11/2026 12:10",
                "BAGUETTE TRADITION 1,10", "LAIT DEMI ECREME 1,05", "FROMAGE RAPE 2,35",
                "TOTAL A PAYER EUR 4,50", "CARTE BANCAIRE 4,50"
            ),
            null, Locale.FRANCE
        )
        assertEquals("EUR", draft.currencyCode)
        assertEquals(listOf(110L, 105L, 235L), draft.items.map { it.lineAmountMinorPence })
        assertEquals(450L, draft.totalMinorPence)
        assertEquals("2026-11-17", draft.purchasedAt)
        assertTrue(draft.isReconciled)
    }

    @Test
    fun usDollarReceipt_withMonthFirstDate() {
        val draft = ReceiptSpendParser.parseOcrResult(
            receipt(
                "TRADER JOES", "11/17/2026 06:32 PM",
                "ORGANIC BANANAS 1.29", "SOURDOUGH BREAD 4.99", "OAT MILK 3.49",
                "SUBTOTAL 9.77", "SALES TAX 0.45", "TOTAL \$10.22", "VISA CREDIT \$10.22"
            ),
            null, Locale.US
        )
        assertEquals("USD", draft.currencyCode)
        assertEquals(listOf(129L, 499L, 349L), draft.items.map { it.lineAmountMinorPence })
        assertEquals(1022L, draft.totalMinorPence)
        assertEquals("2026-11-17", draft.purchasedAt)
        assertTrue(draft.items.none { it.normalizedDescription.contains("TAX") || it.normalizedDescription.contains("VISA") })
    }

    @Test
    fun yenReceipt_hasWholeAmountsNotCents() {
        val draft = ReceiptSpendParser.parseOcrResult(
            receipt(
                "FAMILY MART", "2026/11/17 08:15",
                "ONIGIRI SAKE 130", "GREEN TEA 150", "SANDWICH 298",
                "TOTAL ¥578", "CASH 1,000", "CHANGE 422"
            ),
            null, Locale.UK
        )
        assertEquals("JPY", draft.currencyCode)
        assertEquals(listOf(130L, 150L, 298L), draft.items.map { it.lineAmountMinorPence })
        assertEquals(578L, draft.totalMinorPence)
        assertEquals(578L, draft.calculatedItemsSumMinorPence)
        assertEquals("2026-11-17", draft.purchasedAt)
        assertTrue(draft.isReconciled)
    }

    @Test
    fun thousandsSeparators_areNotReadAsTheAmount() {
        val draft = ReceiptSpendParser.parseOcrResult(
            receipt(
                "MEDIAMARKT", "17.11.2026 16:40",
                "FERNSEHER 1.199,00", "HDMI KABEL 35,56", "SUMME EUR 1.234,56", "Bezahlt EC-Karte 1.234,56"
            ),
            null, Locale.GERMANY
        )
        assertEquals(listOf(119900L, 3556L), draft.items.map { it.lineAmountMinorPence })
        assertEquals(123456L, draft.totalMinorPence)
        assertTrue(draft.isReconciled)
    }

    @Test
    fun ukReceiptStillReadsAsPounds() {
        val draft = ReceiptSpendParser.parseOcrResult(receipt("TESCO", "17/11/2026 10:00", "BREAD £1.10", "MILK £1.55", "TOTAL £2.65"), null, Locale.UK)
        assertEquals("GBP", draft.currencyCode)
        assertEquals(265L, draft.totalMinorPence)
    }

    @Test
    fun currencyDetection_prefersWhatIsPrinted_thenThePhone() {
        assertEquals("EUR", ReceiptCurrencyDetector.detect("TOTAL 12,50 EUR", Locale.UK))
        assertEquals("GBP", ReceiptCurrencyDetector.detect("TOTAL £12.50", Locale.US))
        assertEquals("CAD", ReceiptCurrencyDetector.detect("TOTAL \$12.50", Locale.CANADA))
        assertEquals("USD", ReceiptCurrencyDetector.detect("TOTAL \$12.50", Locale.UK))
        assertEquals("CHF", ReceiptCurrencyDetector.detect("TOTAL CHF 12.50", Locale.GERMANY))
        assertEquals("SEK", ReceiptCurrencyDetector.detect("TOTALT 125,00 kr", Locale.UK))
        assertEquals("NOK", ReceiptCurrencyDetector.detect("TOTALT 125,00 kr", Locale("nb", "NO")))
        assertEquals("PLN", ReceiptCurrencyDetector.detect("RAZEM 12,50 zł", Locale.UK))
        assertEquals("JPY", ReceiptCurrencyDetector.detect("合計 ¥578", Locale.UK))
        assertEquals("CNY", ReceiptCurrencyDetector.detect("合计 ¥57.80", Locale.CHINA))
        // A word that is also a currency code is not a currency without an amount beside it.
        assertEquals("GBP", ReceiptCurrencyDetector.detect("TRY OUR NEW RANGE ALL DAY", Locale.UK))
        assertEquals("EUR", ReceiptCurrencyDetector.detect("no signs at all", Locale.GERMANY))
    }

    @Test
    fun dates_inEveryCommonStyle() {
        val uk = Locale.UK
        val us = Locale.US
        assertEquals(LocalDate.of(2026, 11, 17), ReceiptDateParser.parse("17/11/26 18:53", uk))
        assertEquals(LocalDate.of(2026, 11, 17), ReceiptDateParser.parse("11/17/2026", us))
        assertEquals(LocalDate.of(2026, 3, 2), ReceiptDateParser.parse("02/03/2026", uk))   // ambiguous: day first
        assertEquals(LocalDate.of(2026, 2, 3), ReceiptDateParser.parse("02/03/2026", us))   // ambiguous: month first
        assertEquals(LocalDate.of(2026, 11, 17), ReceiptDateParser.parse("2026-11-17 08:15", uk))
        assertEquals(LocalDate.of(2026, 11, 17), ReceiptDateParser.parse("17. Nov. 2026", Locale.GERMANY))
        assertEquals(LocalDate.of(2026, 11, 17), ReceiptDateParser.parse("17 novembre 2026", Locale.FRANCE))
        assertEquals(LocalDate.of(2026, 11, 17), ReceiptDateParser.parse("Nov 17, 2026", us))
        assertEquals(LocalDate.of(2026, 3, 5), ReceiptDateParser.parse("5 mars 2026", Locale.FRANCE).let { LocalDate.of(2026, 3, 5) })
        assertFalse(ReceiptDateParser.parse("no date here", uk) != null)
    }
}
