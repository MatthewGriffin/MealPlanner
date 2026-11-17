package com.example.chatgpt_mealplanner.core.receipt

import com.example.chatgpt_mealplanner.core.util.MoneyFormat
import java.util.Locale

/**
 * Works out which currency a receipt is in from what is printed on it: an ISO code beside an amount wins, then a currency
 * sign or word. Signs shared by several countries ($, kr, ¥) are settled by the phone's own region, and a receipt with no
 * sign at all is taken to be in the phone's currency.
 */
object ReceiptCurrencyDetector {
    private val isoCodes = listOf(
        "GBP", "EUR", "USD", "CAD", "AUD", "NZD", "CHF", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "RON", "BGN", "ISK",
        "JPY", "CNY", "HKD", "SGD", "KRW", "INR", "TWD", "THB", "MYR", "IDR", "PHP", "VND", "ZAR", "AED", "SAR", "TRY",
        "BRL", "MXN", "ARS", "CLP", "COP", "ILS", "EGP", "NGN", "KES", "RUB", "UAH"
    )

    // A code only counts beside an amount, so a word that is also a code (TRY, ALL) is not mistaken for one.
    private val codeBesideAmount = Regex("""(?:\b(${isoCodes.joinToString("|")})\s?-?\d)|(?:\d\s?(${isoCodes.joinToString("|")})\b)""")

    private val unambiguousSigns: List<Pair<Regex, String>> = listOf(
        Regex("£") to "GBP", Regex("€") to "EUR", Regex("₹") to "INR", Regex("₩|\\bKRW\\b|원") to "KRW", Regex("₺") to "TRY",
        Regex("₪") to "ILS", Regex("₽") to "RUB", Regex("₴") to "UAH", Regex("฿") to "THB", Regex("₫") to "VND",
        Regex("""R\$""") to "BRL", Regex("""(?<!\p{L})zł(?!\p{L})""", RegexOption.IGNORE_CASE) to "PLN", Regex("""(?<!\p{L})Kč(?!\p{L})""", RegexOption.IGNORE_CASE) to "CZK",
        Regex("""\b\d[\d.,]*\s?Ft\b""") to "HUF", Regex("""\b\d[\d.,]*\s?lei\b""", RegexOption.IGNORE_CASE) to "RON",
        Regex("""\bRs\.?\s?\d""") to "INR", Regex("円") to "JPY"
    )

    private val dollarCurrencies = setOf("USD", "CAD", "AUD", "NZD", "SGD", "HKD", "MXN", "ARS", "CLP", "COP", "TWD")
    private val kroneCurrencies = setOf("SEK", "NOK", "DKK", "ISK")

    fun detect(text: String, locale: Locale = Locale.getDefault()): String {
        val phone = MoneyFormat.defaultCurrencyCode(locale)
        val votes = linkedMapOf<String, Int>()
        fun vote(code: String, weight: Int) { votes[code] = (votes[code] ?: 0) + weight }

        codeBesideAmount.findAll(text.uppercase()).forEach { vote(it.groupValues[1].ifEmpty { it.groupValues[2] }, 3) }
        unambiguousSigns.forEach { (regex, code) -> regex.findAll(text).forEach { _ -> vote(code, 1) } }

        // Shared signs are settled by the phone's region.
        val dollars = Regex("""\$""").findAll(text).count()
        if (dollars > 0) vote(if (phone in dollarCurrencies) phone else "USD", dollars)
        val krone = Regex("""\bkr\b\.?""", RegexOption.IGNORE_CASE).findAll(text).count()
        if (krone > 0) vote(if (phone in kroneCurrencies) phone else "SEK", krone)
        val yen = Regex("""[¥￥]""").findAll(text).count()
        if (yen > 0) vote(if (phone == "CNY") "CNY" else "JPY", yen)

        if (votes.isEmpty()) return phone
        val best = votes.values.max()
        val leaders = votes.filterValues { it == best }.keys
        return if (phone in leaders) phone else leaders.first()
    }
}
