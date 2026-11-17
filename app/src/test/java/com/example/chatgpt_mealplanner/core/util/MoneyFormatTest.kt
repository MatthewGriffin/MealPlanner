package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.receipt.ReceiptMoneyInput
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatTest {
    @Test
    fun fractionDigitsFollowTheCurrency() {
        assertEquals(2, MoneyFormat.fractionDigits("GBP"))
        assertEquals(2, MoneyFormat.fractionDigits("EUR"))
        assertEquals(0, MoneyFormat.fractionDigits("JPY"))
        assertEquals(0, MoneyFormat.fractionDigits("KRW"))
        assertEquals(3, MoneyFormat.fractionDigits("KWD"))
        assertEquals(2, MoneyFormat.fractionDigits("NOT-A-CODE"))
    }

    @Test
    fun formatsAnAmountInItsOwnCurrency() {
        assertEquals("£1.50", MoneyFormat.format(150, "GBP", Locale.UK))
        assertEquals("¥350", MoneyFormat.format(350, "JPY", Locale.JAPAN).replace('\uFFE5', '\u00a5'))
        assertEquals("1,50 €", MoneyFormat.format(150, "EUR", Locale.GERMANY).replace(' ', ' '))
        assertEquals("-£0.99", MoneyFormat.format(-99, "GBP", Locale.UK))
    }

    @Test
    fun typedAmountsBecomeMinorUnits() {
        assertEquals(374L, MoneyFormat.toMinorUnits(3.74, "GBP"))
        assertEquals(350L, MoneyFormat.toMinorUnits(350.0, "JPY"))
    }

    @Test
    fun typedAmountsAcceptLocalStyles() {
        assertEquals(374L, ReceiptMoneyInput.parsePositivePence("3.74"))
        assertEquals(374L, ReceiptMoneyInput.parsePositivePence("£3.74"))
        assertEquals(370L, ReceiptMoneyInput.parsePositivePence("3,7"))
        assertEquals(123456L, ReceiptMoneyInput.parsePositivePence("1.234,56", "EUR"))
        assertEquals(123456L, ReceiptMoneyInput.parsePositivePence("1,234.56", "GBP"))
        assertEquals(123400L, ReceiptMoneyInput.parsePositivePence("1,234", "GBP"))
        assertEquals(1234567L, ReceiptMoneyInput.parsePositivePence("1.234.567", "JPY"))
        assertEquals(350L, ReceiptMoneyInput.parsePositivePence("350", "JPY"))
        assertNull(ReceiptMoneyInput.parsePositivePence("3.5", "JPY"))
        assertEquals(1234L, ReceiptMoneyInput.parsePositivePence("1.234", "JPY"))
        assertNull(ReceiptMoneyInput.parsePositivePence("1.234", "GBP"))
        assertNull(ReceiptMoneyInput.parsePositivePence("-1.00"))
        assertNull(ReceiptMoneyInput.parsePositivePence("abc"))
    }

    @Test
    fun readableText() {
        assertEquals("3.74", ReceiptMoneyInput.format(374, "GBP"))
        assertEquals("350", ReceiptMoneyInput.format(350, "JPY"))
    }
}
