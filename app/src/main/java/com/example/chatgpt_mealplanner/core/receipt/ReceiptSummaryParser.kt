package com.example.chatgpt_mealplanner.core.receipt

import android.util.Log

data class ReceiptSummaryResult(
    val retailerId: String,
    val purchasedAt: String?,
    val dateNeedsConfirmation: Boolean,
    val finalPaidTotalMinorPence: Long?,
    val grossTotalMinorPence: Long?,
    val savingsMinorPence: Long,
    val cardPaymentMinorPence: Long?,
    val transactionRef: String?,
    val summaryArithmeticValid: Boolean,
    val isHighConfidence: Boolean
)

object ReceiptSummaryParser {

    // Whole words, so products such as TOTALLY NUTS and BALANCED MUESLI aren't read as totals.
    private val totalLabel = Regex("""^(TOTAL|BALANCE)\b""")

    fun parseSummary(ocrResult: ReceiptOcrResult, locale: java.util.Locale = java.util.Locale.getDefault()): ReceiptSummaryResult {
        val retailerId = detectRetailer(ocrResult.fullText)
        val (purchasedAt, dateNeedsConfirmation) = parseDate(ocrResult.fullText, locale)
        val transactionRef = parseTransactionRef(ocrResult.fullText)

        var explicitGrossPence: Long? = null
        var explicitSavingsPence = 0L
        var explicitPaidPence: Long? = null
        var cardPaymentPence: Long? = null

        val lines = ocrResult.lines
        val alignment = ReceiptRowAligner.align(lines)
        fun alignedAmount(lineIndex: Int): Long? =
            alignment.priceLineFor[lineIndex]?.let { ReceiptMoneyParser.parseAmountPence(lines[it].text) }

        // 1. Savings / Discount Detection via Bidirectional Spatial Proximity (deltaY <= 30px)
        val savingsLabelLine = lines.firstOrNull {
            val norm = ReceiptDescriptionNormalizer.normalize(it.text)
            norm.contains("SAVIINGS") || norm.contains("SAVINGS") || norm.contains("SAVING") || norm.contains("DISCOUNT") || ReceiptVocabulary.isDiscountLine(norm)
        }

        if (savingsLabelLine != null) {
            val amountInLabel = ReceiptMoneyParser.parseAmountPence(savingsLabelLine.text)
            if (amountInLabel != null && amountInLabel != 0L) {
                explicitSavingsPence = Math.abs(amountInLabel)
            } else {
                alignedAmount(lines.indexOf(savingsLabelLine))?.let { explicitSavingsPence = Math.abs(it) }
                // Search bidirectionally (before or after label) for discount price token within deltaY <= 30px
                val nearbySavingsLine = lines.firstOrNull { line ->
                    line != savingsLabelLine &&
                    Math.abs(line.centerY - savingsLabelLine.centerY) <= 30 &&
                    ReceiptMoneyParser.parseAmountPence(line.text) != null
                }
                if (explicitSavingsPence == 0L && nearbySavingsLine != null) {
                    val parsedSav = ReceiptMoneyParser.parseAmountPence(nearbySavingsLine.text)
                    if (parsedSav != null) {
                        explicitSavingsPence = Math.abs(parsedSav)
                    }
                }
            }
        }

        // Retailers such as Morrisons print markdowns as plain negative amounts with no "savings" label.
        if (explicitSavingsPence == 0L) {
            explicitSavingsPence = lines.sumOf { line ->
                val norm = ReceiptDescriptionNormalizer.normalize(line.text)
                if (totalLabel.containsMatchIn(norm)) 0L
                else ReceiptMoneyParser.parseAmountPence(line.text)?.takeIf { it < 0L }?.let { -it } ?: 0L
            }
        }

        // 2. Card Payment & Total Line Parsing
        lines.forEachIndexed { index, line ->
            val norm = ReceiptDescriptionNormalizer.normalize(line.text)

            if (ReceiptVocabulary.isCardLine(norm) && !ReceiptVocabulary.isTotalLine(norm) || norm.contains("CARD £") || norm.startsWith("CARD") || norm.contains("VISA") || norm.contains("MASTERCARD") || norm.contains("TENDERED") || norm.contains("CONTACTLESS")) {
                val priceInLine = ReceiptMoneyParser.parseAmountPence(line.text)
                if (priceInLine != null && priceInLine > 0) {
                    cardPaymentPence = priceInLine
                } else {
                    val nearbyCardLine = lines.firstOrNull { l ->
                        Math.abs(l.centerY - line.centerY) <= 24 &&
                        ReceiptMoneyParser.parseAmountPence(l.text) != null
                    }
                    val cardAmount = alignedAmount(index)
                        ?: nearbyCardLine?.let { ReceiptMoneyParser.parseAmountPence(it.text) }
                    if (cardAmount != null) {
                        cardPaymentPence = cardAmount
                    }
                }
            }

            if (totalLabel.containsMatchIn(norm) || ReceiptVocabulary.startsWithTotal(norm) || norm.contains("AMOUNT PAID") || norm.contains("BALANCE DUE")) {
                val parsedTotal = ReceiptMoneyParser.parseAmountPence(line.text)
                    ?: alignedAmount(index)
                    ?: lines.firstOrNull { l -> Math.abs(l.centerY - line.centerY) <= 24 && ReceiptMoneyParser.parseAmountPence(l.text) != null }?.let { ReceiptMoneyParser.parseAmountPence(it.text) }

                if (parsedTotal != null && parsedTotal > 0) {
                    if (norm.contains("SAVINGS") || norm.contains("SAVIINGS")) {
                        // Skip savings line
                    } else if (explicitGrossPence == null) {
                        explicitGrossPence = parsedTotal
                    } else if (explicitPaidPence == null || norm.contains("PAID") || norm.contains("BALANCE")) {
                        explicitPaidPence = parsedTotal
                    }
                }
            }
        }

        val finalPaid: Long? = explicitPaidPence
            ?: cardPaymentPence
            ?: explicitGrossPence

        val gross: Long? = when {
            explicitGrossPence != null && explicitPaidPence != null && explicitGrossPence != explicitPaidPence -> explicitGrossPence
            finalPaid != null && explicitSavingsPence > 0L -> finalPaid + explicitSavingsPence
            else -> explicitGrossPence ?: finalPaid
        }

        val mathChecksOut = gross != null && finalPaid != null && (gross - explicitSavingsPence == finalPaid)
        val cardMatchesPaid = cardPaymentPence == null || finalPaid == null || cardPaymentPence == finalPaid
        val summaryArithmeticValid = mathChecksOut && cardMatchesPaid
        val isHighConfidence = summaryArithmeticValid && (finalPaid ?: 0L) > 0L

        if (com.example.chatgpt_mealplanner.BuildConfig.DEBUG) try {
            Log.d("RECEIPT_PIPELINE_TRACE", "[SUMMARY_PARSER] explicitGross=$explicitGrossPence, savings=$explicitSavingsPence, explicitPaid=$explicitPaidPence, cardPence=$cardPaymentPence -> finalPaid=$finalPaid, gross=$gross")
        } catch (ignored: Throwable) {}

        return ReceiptSummaryResult(
            retailerId = retailerId,
            purchasedAt = purchasedAt,
            dateNeedsConfirmation = dateNeedsConfirmation,
            finalPaidTotalMinorPence = finalPaid,
            grossTotalMinorPence = gross,
            savingsMinorPence = explicitSavingsPence,
            cardPaymentMinorPence = cardPaymentPence,
            transactionRef = transactionRef,
            summaryArithmeticValid = summaryArithmeticValid,
            isHighConfidence = isHighConfidence
        )
    }

    private fun detectRetailer(fullText: String): String {
        val upper = fullText.uppercase()
        return when {
            upper.contains("ASDA") || upper.contains("WALMART") -> "ASDA"
            upper.contains("FOOD WAREHOUSE") -> "FOOD_WAREHOUSE"
            upper.contains("ICELAND") -> "ICELAND"
            else -> "OTHER"
        }
    }

    private fun parseDate(fullText: String, locale: java.util.Locale): Pair<String?, Boolean> {
        val date = ReceiptDateParser.parse(fullText, locale)
        return if (date != null) Pair(date.toString(), false) else Pair(null, true) // Date needs user confirmation!
    }

    private fun parseTransactionRef(fullText: String): String? {
        val refMatch = Regex("""\b(?:REF|TRX|ORDER|NO\.)\s*[:#]?\s*([A-Z0-9]{4,16})\b""", RegexOption.IGNORE_CASE).find(fullText)
        return refMatch?.groupValues?.get(1)?.uppercase()
    }
}
