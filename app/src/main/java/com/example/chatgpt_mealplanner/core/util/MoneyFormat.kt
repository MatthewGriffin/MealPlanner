package com.example.chatgpt_mealplanner.core.util

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Amounts are stored as whole minor units (pence, cents, yen...) of the receipt's own currency. This is the one place that
 * turns them into text, so a euro receipt is never shown as pounds and a yen receipt is not divided by 100.
 */
object MoneyFormat {
    /** How many decimal places the currency has (2 for pounds and euros, 0 for yen). Unknown codes count as 2. */
    fun fractionDigits(currencyCode: String): Int =
        runCatching { Currency.getInstance(currencyCode).defaultFractionDigits }.getOrDefault(2).let { if (it < 0) 2 else it }

    /** The phone's own currency (from its region), or GBP when the region has none. */
    fun defaultCurrencyCode(locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(locale).currencyCode }.getOrDefault("GBP")

    fun toDecimal(minorUnits: Long, currencyCode: String): BigDecimal = BigDecimal.valueOf(minorUnits, fractionDigits(currencyCode))

    /** A typed decimal amount (3.50) as minor units of the currency, rounded to the nearest. */
    fun toMinorUnits(amount: Double, currencyCode: String): Long =
        BigDecimal.valueOf(amount).movePointRight(fractionDigits(currencyCode)).setScale(0, java.math.RoundingMode.HALF_UP).toLong()

    /** "£1.50", "€1,50" or "¥150", shaped for the phone's locale. */
    fun format(minorUnits: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(currencyCode) }
        format.minimumFractionDigits = fractionDigits(currencyCode)
        format.maximumFractionDigits = fractionDigits(currencyCode)
        return format.format(toDecimal(minorUnits, currencyCode))
    }

    /** Just the symbol for field labels, such as "Price (€)". */
    fun symbol(currencyCode: String, locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(currencyCode).getSymbol(locale) }.getOrDefault(currencyCode)
}
