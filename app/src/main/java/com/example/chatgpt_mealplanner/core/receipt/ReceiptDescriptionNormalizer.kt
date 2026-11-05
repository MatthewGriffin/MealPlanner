package com.example.chatgpt_mealplanner.core.receipt

object ReceiptDescriptionNormalizer {

    private val nonProductPhrases = listOf(
        "subtotal", "sub total", "savings", "discount", "multibuy", "promo",
        "cashier", "operator", "receipt", "order no", "transaction", "thank you", "visit us",
        "mastercard", "apple pay", "contactless", "approved", "coupon", "refund",
        "bag charge", "carrier bag", "store #", "st#"
    )

    // These must match whole, so STILL WATER, OREGANO, HOTEL CHOCOLAT and TOTALLY NUTS stay products.
    private val nonProductWords = Regex("""\b(total|balance|change|vat|tax|till|store|address|tel|phone|visa|pos|reg)\b""")

    private val labelsAllowingOneEdit = listOf("TOTAL", "SUBTOTAL", "SAVINGS")

    // BALANCED is a real product word one letter from BALANCE, so this label must match exactly.
    private const val LABEL_WITHOUT_EDITS = "BALANCE"

    /** OCR swaps these look-alikes, so they're treated as the same character when reading labels. */
    private fun lookAlikeForm(word: String): String = word.map {
        when (it) {
            '0' -> 'O'
            '1', 'I', '|' -> 'L'
            '5' -> 'S'
            else -> it
        }
    }.joinToString("")

    private fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        val prefix = shorter.zip(longer).takeWhile { (x, y) -> x == y }.size
        val shorterRest = if (shorter.length == longer.length) prefix + 1 else prefix
        return shorter.drop(shorterRest) == longer.drop(prefix + 1)
    }

    /** Repairs summary labels only, so T0TAL and TOTALL: are read as TOTAL and products stay as printed. */
    private fun repairSummaryLabels(text: String): String = text.split(' ').joinToString(" ") { word ->
        val core = word.trimEnd(':', '.', ';', ',')
        val form = lookAlikeForm(core)
        val label = labelsAllowingOneEdit.firstOrNull { withinOneEdit(form, lookAlikeForm(it)) }
            ?: LABEL_WITHOUT_EDITS.takeIf { form == lookAlikeForm(it) }
        if (label != null) label + word.removePrefix(core) else word
    }

    fun normalize(rawText: String): String {
        var text = rawText.uppercase().trim()

        // Remove leading/trailing non-alphanumeric noise (e.g. "* CHKN BRST *" -> "CHKN BRST")
        text = text.replace(Regex("^[^A-Z0-9]+|[^A-Z0-9]+$"), "")

        // Collapse multiple spaces into single space
        text = text.replace(Regex("\\s+"), " ")

        return repairSummaryLabels(text)
    }

    fun isNonProductLine(normalizedText: String): Boolean {
        val lower = normalizedText.lowercase()
        return nonProductPhrases.any { lower.contains(it) } || nonProductWords.containsMatchIn(lower)
    }
}
