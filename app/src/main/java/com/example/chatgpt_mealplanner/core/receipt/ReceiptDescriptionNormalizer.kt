package com.example.chatgpt_mealplanner.core.receipt

object ReceiptDescriptionNormalizer {

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
        val nonProductKeywords = listOf(
            "subtotal", "sub total", "total", "balance", "balance due", "change",
            "vat", "tax", "tax rate", "savings", "discount", "multibuy", "promo",
            "cashier", "operator", "till", "store", "address", "tel", "phone",
            "receipt", "order no", "transaction", "thank you", "visit us",
            "card", "visa", "mastercard", "apple pay", "contactless", "approved",
            "coupon", "refund", "bag charge", "carrier bag",
            "hore", "j3gh", "mch", "pos", "reg", "st#", "store #"
        )
        return nonProductKeywords.any { lower.contains(it) }
    }
}
