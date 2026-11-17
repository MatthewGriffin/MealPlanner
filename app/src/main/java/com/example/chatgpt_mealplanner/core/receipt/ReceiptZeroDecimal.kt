package com.example.chatgpt_mealplanner.core.receipt

/**
 * Currencies with no minor unit (yen, won, dong...) print whole amounts: "ONIGIRI 130". The parser reads prices with two
 * decimals, so for these receipts each whole-number price is rewritten as "130.00" and currency marks are dropped; the
 * finished draft is then scaled back down by 100 ([ReceiptSpendDraft.scaledToWholeUnits]).
 */
object ReceiptZeroDecimal {
    private val marks = Regex("""[¥￥円₩원₫]|\b(?:JPY|KRW|VND|IDR|CLP|ISK|PYG)\b""", RegexOption.IGNORE_CASE)
    private val wholeAmount = Regex("""^(-?)(\d{1,3}(?:[.,]\d{3})+|\d{1,6})$""")
    private val countLine = Regex("""ITEMS?\s*(SOLD|COUNT)|NO\.?\s*(OF\s*)?ITEMS""", RegexOption.IGNORE_CASE)

    fun normalise(ocr: ReceiptOcrResult): ReceiptOcrResult {
        val lines = ocr.lines.map { line ->
            if (countLine.containsMatchIn(line.text)) return@map line
            val tokens: List<ReceiptOcrElement> = line.elements.ifEmpty {
                line.text.split(' ').filter { it.isNotBlank() }.map { ReceiptOcrElement(it, line.left, line.top, line.right, line.bottom, line.centerY) }
            }
            val cleaned = tokens.mapNotNull { e ->
                val text = e.text.replace(marks, "").trim()
                if (text.isEmpty()) null else e.copy(text = text)
            }.toMutableList()
            val last = cleaned.lastOrNull() ?: return@map line.copy(text = "", elements = emptyList())
            val match = wholeAmount.find(last.text)
            val hasWordBefore = cleaned.dropLast(1).any { e -> e.text.any { it.isLetter() } }
            if (match != null && (hasWordBefore || cleaned.size == 1)) {
                val digits = match.groupValues[2].replace(".", "").replace(",", "")
                cleaned[cleaned.lastIndex] = last.copy(text = "${match.groupValues[1]}$digits.00")
            }
            line.copy(text = cleaned.joinToString(" ") { it.text }, elements = if (line.elements.isEmpty()) emptyList() else cleaned)
        }
        return ocr.copy(lines = lines, fullText = lines.joinToString("\n") { it.text })
    }
}
