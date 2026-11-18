package com.example.chatgpt_mealplanner.core.receipt

import com.example.chatgpt_mealplanner.core.ai.provider.ReadReceipt
import com.example.chatgpt_mealplanner.core.util.MoneyFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

/** Turns a receipt Claude read from the photo into the same reviewable draft the phone's own reader makes. */
object ReceiptReadingDraft {

    /** "3.74", "1,234.56", "1.234,56", "£3.74" or "130" as minor units of [currencyCode]; null if it isn't an amount. */
    fun minorUnits(text: String?, currencyCode: String): Long? {
        if (text.isNullOrBlank()) return null
        val digits = MoneyFormat.fractionDigits(currencyCode)
        var t = text.trim().replace(Regex("""[^\d.,\-]"""), "")
        if (t.isEmpty() || t == "-") return null
        val negative = t.startsWith("-")
        t = t.trimStart('-')
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        val decimalMark = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) '.' else ','
            lastDot >= 0 || lastComma >= 0 -> {
                val mark = if (lastDot >= 0) '.' else ','
                val after = t.length - t.lastIndexOf(mark) - 1
                // A lone mark followed by exactly three digits is a thousands mark when the currency can't have three decimals.
                if (after == 3 && digits < 3) null else mark
            }
            else -> null
        }
        val normal = if (decimalMark == null) t.replace(".", "").replace(",", "")
        else t.substringBeforeLast(decimalMark).replace(".", "").replace(",", "") + "." + t.substringAfterLast(decimalMark)
        val value = normal.toBigDecimalOrNull() ?: return null
        val minor = value.setScale(digits, RoundingMode.HALF_UP).movePointRight(digits).toBigInteger()
        if (minor.bitLength() > 62) return null
        return if (negative) -minor.toLong() else minor.toLong()
    }

    /** The retailer id used everywhere else: the known chains keep their short ids so a receipt reads the same either way. */
    fun retailerId(name: String): String {
        val known = ReceiptSummaryParser.detectRetailerId(name)
        if (known != "OTHER") return known
        val cleaned = name.uppercase().replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_')
        return cleaned.ifEmpty { "OTHER" }.take(40)
    }

    fun toDraft(read: ReadReceipt, sourceImagePath: String?, locale: Locale = Locale.getDefault()): ReceiptSpendDraft {
        val currency = read.currency.takeIf { c -> c.length == 3 && runCatching { java.util.Currency.getInstance(c) }.isSuccess }
            ?: MoneyFormat.defaultCurrencyCode(locale)
        val items = read.items.mapNotNull { line ->
            val amount = minorUnits(line.amount, currency) ?: return@mapNotNull null
            if (amount <= 0L) return@mapNotNull null
            val raw = line.printedText
            ReceiptSpendItemDraft(
                rawDescription = raw,
                normalizedDescription = line.name,
                lineAmountMinorPence = amount,
                quantity = line.quantity,
                unitPriceMinorPence = if (line.quantity > 1) amount / line.quantity else null,
                category = ReceiptSpendCategory.fromName(line.category).name,
                confidence = 0.95f,
                genericName = line.genericName,
                brand = line.brand,
                foodCategory = line.foodCategory,
                storageLocation = line.storageLocation
            )
        }
        val savings = read.discounts.mapNotNull { minorUnits(it, currency) }.sumOf { kotlin.math.abs(it) }
        val paid = minorUnits(read.totalPaid, currency)?.takeIf { it > 0L }
        val itemsSum = items.sumOf { it.lineAmountMinorPence }
        val gross = minorUnits(read.subtotal, currency)?.takeIf { it > 0L } ?: if (paid != null) paid + savings else null
        val arithmeticValid = gross == null || paid == null || gross - savings == paid
        val date = read.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it.year in 2000..2100 }?.toString()
        val retailer = retailerId(read.retailer)
        val review = evaluateReceiptReview(
            retailerId = retailer,
            purchasedAt = date,
            dateNeedsConfirmation = date == null,
            paidTotalPence = paid,
            grossTargetPence = gross,
            savingsPence = savings,
            recognisedGrossPence = itemsSum,
            itemCount = items.sumOf { it.quantity },
            expectedItemCount = null,
            summaryArithmeticValid = arithmeticValid,
            currencyCode = currency
        )
        return ReceiptSpendDraft(
            retailerId = retailer,
            purchasedAt = date,
            dateNeedsConfirmation = date == null,
            totalMinorPence = paid,
            explicitGrossTotalMinorPence = gross,
            explicitSavingsMinorPence = savings,
            explicitFinalPaidMinorPence = paid,
            recognisedGrossItemSpendMinorPence = itemsSum,
            unrecognisedGrossSpendMinorPence = review.unrecognisedGrossPence,
            recognisedGrossOverageMinorPence = review.overagePence,
            calculatedItemsSumMinorPence = itemsSum,
            discountMinorPence = savings,
            itemCount = items.size,
            expectedItemCount = null,
            discrepancyMinorPence = review.discrepancyPence,
            summaryArithmeticValid = arithmeticValid,
            isReconciled = review.isReconciled,
            needsReview = review.needsReview,
            canSave = review.canSave,
            reviewWarning = review.warning,
            fingerprint = receiptFingerprint(retailer, date, paid, null),
            sourceImagePath = sourceImagePath,
            items = items,
            currencyCode = currency
        )
    }
}
