package com.example.chatgpt_mealplanner.core.receipt

object ReceiptDescriptionNormalizer {

    private val nonProductPhrases = listOf(
        "subtotal", "sub total", "total", "balance", "savings", "discount", "multibuy", "promo",
        "cashier", "operator", "receipt", "order no", "transaction", "thank you", "visit us",
        "mastercard", "apple pay", "contactless", "approved", "coupon", "refund",
        "bag charge", "carrier bag", "store #", "st#"
    )

    // Short words must match whole, so STILL WATER, OREGANO and HOTEL CHOCOLAT stay products.
    private val nonProductWords = Regex("""\b(change|vat|tax|till|store|address|tel|phone|visa|pos|reg)\b""")

    fun normalize(rawText: String): String {
        var text = rawText.uppercase().trim()

        // Remove leading/trailing non-alphanumeric noise (e.g. "* CHKN BRST *" -> "CHKN BRST")
        text = text.replace(Regex("^[^A-Z0-9]+|[^A-Z0-9]+$"), "")

        // Collapse multiple spaces into single space
        text = text.replace(Regex("\\s+"), " ")

        return text
    }

    fun isNonProductLine(normalizedText: String): Boolean {
        val lower = normalizedText.lowercase()
        return nonProductPhrases.any { lower.contains(it) } || nonProductWords.containsMatchIn(lower)
    }
}
