package com.example.chatgpt_mealplanner.core.receipt

import android.util.Log
import java.security.MessageDigest
import java.util.UUID

data class ReceiptSpendItemDraft(
    val id: String = UUID.randomUUID().toString(),
    val rawDescription: String,
    val normalizedDescription: String,
    val lineAmountMinorPence: Long,
    val quantity: Int = 1,
    val unitPriceMinorPence: Long? = null,
    val category: String = ReceiptSpendCategory.UNCATEGORISED.name,
    val isDiscount: Boolean = false,
    val confidence: Float = 0.9f,
    val linkedInventoryItemId: String? = null,
    val linkedShoppingListItemId: String? = null,
    /** The product without its brand or pack words ("Warburtons Soft White Bread" is "White bread"); what the pantry holds. */
    val genericName: String? = null,
    val brand: String? = null,
    /** A pantry [com.example.chatgpt_mealplanner.core.model.FoodCategory] and StorageLocation name, when Claude gave them. */
    val foodCategory: String? = null,
    val storageLocation: String? = null,
    /** True when the link to the shopping list or pantry was chosen by the app, not the user. */
    val autoLinked: Boolean = false,
    /** True once the user has chosen (or cleared) the link by hand; the app never changes it after that. */
    val linkLocked: Boolean = false
)

data class ReceiptSpendDraft(
    val id: String = UUID.randomUUID().toString(),
    val retailerId: String,
    val purchasedAt: String?,
    val dateNeedsConfirmation: Boolean = false,
    val totalMinorPence: Long?,
    val explicitGrossTotalMinorPence: Long?,
    val explicitSavingsMinorPence: Long,
    val explicitFinalPaidMinorPence: Long?,
    val recognisedGrossItemSpendMinorPence: Long,
    val unrecognisedGrossSpendMinorPence: Long,
    val recognisedGrossOverageMinorPence: Long,
    val calculatedItemsSumMinorPence: Long,
    val discountMinorPence: Long,
    val itemCount: Int,
    val expectedItemCount: Int?,
    val discrepancyMinorPence: Long,
    val summaryArithmeticValid: Boolean,
    val isReconciled: Boolean,
    val needsReview: Boolean,
    val canSave: Boolean,
    val userConfirmedSummary: Boolean = false,
    val isProbableDuplicate: Boolean = false,
    val reviewWarning: String?,
    val fingerprint: String,
    val sourceImagePath: String?,
    val transactionRef: String? = null,
    val items: List<ReceiptSpendItemDraft>,
    /** ISO 4217 code; every amount in this draft is in the minor units of this currency. */
    val currencyCode: String = "GBP"
) {
    /**
     * A receipt in a currency with no minor unit was read with two phantom decimals (130 as 130.00); this divides every amount
     * by 100 so they are real whole units again, and rebuilds the review text in them.
     */
    fun scaledToWholeUnits(): ReceiptSpendDraft = copy(
        totalMinorPence = totalMinorPence?.div(100),
        explicitGrossTotalMinorPence = explicitGrossTotalMinorPence?.div(100),
        explicitSavingsMinorPence = explicitSavingsMinorPence / 100,
        explicitFinalPaidMinorPence = explicitFinalPaidMinorPence?.div(100),
        recognisedGrossItemSpendMinorPence = recognisedGrossItemSpendMinorPence / 100,
        unrecognisedGrossSpendMinorPence = unrecognisedGrossSpendMinorPence / 100,
        recognisedGrossOverageMinorPence = recognisedGrossOverageMinorPence / 100,
        calculatedItemsSumMinorPence = calculatedItemsSumMinorPence / 100,
        discountMinorPence = discountMinorPence / 100,
        discrepancyMinorPence = discrepancyMinorPence / 100,
        items = items.map { it.copy(lineAmountMinorPence = it.lineAmountMinorPence / 100, unitPriceMinorPence = it.unitPriceMinorPence?.div(100)) }
    ).withUserEdits()

    fun recomputeWithPaidTotal(newPaidTotalPence: Long): ReceiptSpendDraft =
        withUserEdits(paidTotalPence = newPaidTotalPence).copy(userConfirmedSummary = true)

    /** Applies a correction from the review screen and recalculates everything derived from it. */
    fun withUserEdits(
        purchasedAt: String? = this.purchasedAt,
        paidTotalPence: Long? = this.totalMinorPence,
        items: List<ReceiptSpendItemDraft> = this.items
    ): ReceiptSpendDraft {
        val paidChanged = paidTotalPence != totalMinorPence
        val identityChanged = paidChanged || purchasedAt != this.purchasedAt
        val gross = if (paidChanged) paidTotalPence?.plus(explicitSavingsMinorPence) else explicitGrossTotalMinorPence
        val summaryValid = paidChanged || summaryArithmeticValid
        val recognisedGross = items.filter { !it.isDiscount }.sumOf { it.lineAmountMinorPence }
        val dateMissing = purchasedAt == null
        val review = evaluateReceiptReview(
            retailerId = retailerId,
            purchasedAt = purchasedAt,
            dateNeedsConfirmation = dateMissing,
            paidTotalPence = paidTotalPence,
            grossTargetPence = gross,
            savingsPence = explicitSavingsMinorPence,
            recognisedGrossPence = recognisedGross,
            itemCount = items.sumOf { it.quantity },
            expectedItemCount = expectedItemCount,
            summaryArithmeticValid = summaryValid,
            currencyCode = currencyCode
        )
        val stillProbableDuplicate = isProbableDuplicate && !identityChanged

        return copy(
            purchasedAt = purchasedAt,
            dateNeedsConfirmation = dateMissing,
            totalMinorPence = paidTotalPence,
            explicitFinalPaidMinorPence = paidTotalPence,
            explicitGrossTotalMinorPence = gross,
            recognisedGrossItemSpendMinorPence = recognisedGross,
            unrecognisedGrossSpendMinorPence = review.unrecognisedGrossPence,
            recognisedGrossOverageMinorPence = review.overagePence,
            calculatedItemsSumMinorPence = recognisedGross,
            itemCount = items.size,
            discrepancyMinorPence = review.discrepancyPence,
            summaryArithmeticValid = summaryValid,
            isReconciled = review.isReconciled,
            needsReview = review.needsReview,
            canSave = review.canSave,
            userConfirmedSummary = userConfirmedSummary || paidChanged,
            isProbableDuplicate = stillProbableDuplicate,
            reviewWarning = if (stillProbableDuplicate) DUPLICATE_RECEIPT_WARNING else review.warning,
            fingerprint = receiptFingerprint(retailerId, purchasedAt, paidTotalPence, transactionRef),
            items = items
        )
    }
}

const val DUPLICATE_RECEIPT_WARNING =
    "Possible duplicate receipt: A receipt with this retailer, date, and total was already saved."

internal data class ReceiptReviewState(
    val unrecognisedGrossPence: Long,
    val overagePence: Long,
    val discrepancyPence: Long,
    val isReconciled: Boolean,
    val needsReview: Boolean,
    val canSave: Boolean,
    val warning: String?
)

private fun money(pence: Long, currencyCode: String): String = com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(pence, currencyCode)

internal fun evaluateReceiptReview(
    retailerId: String,
    purchasedAt: String?,
    dateNeedsConfirmation: Boolean,
    paidTotalPence: Long?,
    grossTargetPence: Long?,
    savingsPence: Long,
    recognisedGrossPence: Long,
    itemCount: Int,
    expectedItemCount: Int?,
    summaryArithmeticValid: Boolean,
    currencyCode: String = "GBP"
): ReceiptReviewState {
    fun pounds(pence: Long) = money(pence, currencyCode)
    val unrecognised = if (grossTargetPence != null) maxOf(0L, grossTargetPence - recognisedGrossPence) else 0L
    val overage = if (grossTargetPence != null) maxOf(0L, recognisedGrossPence - grossTargetPence) else 0L
    val discrepancy = if (grossTargetPence != null) grossTargetPence - recognisedGrossPence else 0L
    val itemCountMatches = expectedItemCount == null || itemCount == expectedItemCount
    val isReconciled = discrepancy == 0L && overage == 0L && itemCountMatches
    val paidMissing = paidTotalPence == null || paidTotalPence == 0L
    val dateMissing = dateNeedsConfirmation || purchasedAt == null

    val warning = when {
        paidMissing ->
            "We couldn't detect the amount paid. Please review or enter the receipt total before saving."
        dateMissing ->
            "Receipt date could not be verified. Please confirm or enter receipt date before saving."
        !summaryArithmeticValid ->
            "Summary total arithmetic mismatch (Gross ${pounds(grossTargetPence ?: 0L)} - Savings ${pounds(savingsPence)} != Paid ${pounds(paidTotalPence!!)}). Please confirm totals before saving."
        overage > 0L ->
            "Recognised item lines (${pounds(recognisedGrossPence)}) exceed receipt gross total (${pounds(grossTargetPence!!)}). Please review items before saving."
        !itemCountMatches ->
            "Only $itemCount of $expectedItemCount item lines were recognized. Your receipt spend (${pounds(paidTotalPence!!)}) can still be saved."
        !isReconciled && grossTargetPence != null && grossTargetPence > 0L ->
            "Discrepancy of ${pounds(kotlin.math.abs(discrepancy))} detected between gross total (${pounds(grossTargetPence)}) and items sum (${pounds(recognisedGrossPence)}). Your receipt spend can still be saved."
        else -> null
    }

    return ReceiptReviewState(
        unrecognisedGrossPence = unrecognised,
        overagePence = overage,
        discrepancyPence = discrepancy,
        isReconciled = isReconciled,
        needsReview = paidMissing || !isReconciled || dateMissing || !summaryArithmeticValid || overage > 0L,
        canSave = !paidMissing && retailerId.isNotBlank() && !dateMissing,
        warning = warning
    )
}

internal fun receiptFingerprint(retailerId: String, purchasedAt: String?, paidTotalPence: Long?, transactionRef: String?): String {
    val raw = "${retailerId}_${purchasedAt ?: "NO_DATE"}_${paidTotalPence ?: "NO_PAID"}_${transactionRef ?: "NO_REF"}"
    return MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Reads an amount typed by the user, such as "3.74", "£3.74", "3,7", "1.234,56" or (for a currency with no cents) "350". */
object ReceiptMoneyInput {
    fun parsePositivePence(text: String, currencyCode: String = "GBP"): Long? {
        val digits = com.example.chatgpt_mealplanner.core.util.MoneyFormat.fractionDigits(currencyCode)
        if (text.contains('-')) return null
        // Drop a currency sign or code at either end and any spaces.
        val core = text.trim().replace(Regex("""^[^\d]+|[^\d]+$"""), "").replace(" ", "")
        if (core.isEmpty()) return null
        val marks = core.filter { it == '.' || it == ',' }
        val number = when {
            marks.isEmpty() -> core
            // "1.234.567": the same mark repeated can only be thousands marks.
            marks.length > 1 && marks.toSet().size == 1 -> {
                val groups = core.split(marks[0])
                if (groups[0].length in 1..3 && groups.drop(1).all { it.length == 3 }) groups.joinToString("") else return null
            }
            else -> {
                val last = core.lastIndexOfAny(charArrayOf('.', ','))
                val whole = core.substring(0, last).replace(".", "").replace(",", "")
                val fraction = core.substring(last + 1)
                // One mark followed by exactly three digits is a thousands mark when the currency cannot have three decimals:
                // "1,234" in most currencies, and also "1.234" where there are no cents at all (yen, won). "1.234" in pounds is a typo.
                val thousands = marks.length == 1 && fraction.length == 3 &&
                    ((marks[0] == ',' && digits < 3) || (marks[0] == '.' && digits == 0))
                if (thousands) whole + fraction else "$whole.$fraction"
            }
        }
        val pattern = if (digits == 0) Regex("""\d{1,9}""") else Regex("""\d{1,9}(\.\d{1,$digits})?""")
        if (!pattern.matches(number)) return null
        return java.math.BigDecimal(number).movePointRight(digits).longValueExact().takeIf { it > 0L }
    }

    fun format(pence: Long, currencyCode: String = "GBP"): String =
        com.example.chatgpt_mealplanner.core.util.MoneyFormat.toDecimal(pence, currencyCode).toPlainString()
}

object ReceiptMoneyParser {
    // Matches £3.74, 3.74, 3,74, -£3.60, -3.60, C12.00, L2.40, e38.88, g4.00
    // The whole part may carry thousands marks: 1,234.56 and 1.234,56 as well as 3.74.
    private const val WHOLE = """(\d{1,3}(?:[.,\u00a0\u202f]\d{3})+|\d{1,6})"""
    private val rightPriceRegex = Regex("""(-?)\s*(?:[£CLe gqPS$€]\s*|\b)$WHOLE[.,]([0-9]{2})(?:\s+[A-Z*])?$""", RegexOption.IGNORE_CASE)
    private val generalMoneyRegex = Regex("""(-?)\s*(?:[£CLe gqPS$€]\s*|\b)$WHOLE[.,]([0-9]{2})(?!\d)""", RegexOption.IGNORE_CASE)

    fun parseAmountPence(text: String): Long? {
        if (text.contains("%")) {
            return null // PERCENTAGES MUST NEVER PARSE AS MONEY!
        }
        val match = generalMoneyRegex.find(text) ?: return null
        val isNegative = match.groupValues[1] == "-"
        val pounds = match.groupValues[2].filter { it.isDigit() }.toLongOrNull() ?: return null
        val pence = match.groupValues[3].toLongOrNull() ?: return null
        val totalPence = (pounds * 100L) + pence
        return if (isNegative) -totalPence else totalPence
    }

    fun parseRightAlignedAmountPence(text: String): Long? {
        if (text.contains("%")) {
            return null // PERCENTAGES MUST NEVER PARSE AS MONEY!
        }
        val match = rightPriceRegex.find(text.trim()) ?: return null
        val isNegative = match.groupValues[1] == "-"
        val pounds = match.groupValues[2].filter { it.isDigit() }.toLongOrNull() ?: return null
        val pence = match.groupValues[3].toLongOrNull() ?: return null
        val totalPence = (pounds * 100L) + pence
        return if (isNegative) -totalPence else totalPence
    }
}

object ReceiptSpendParser {

    const val RECEIPT_PARSER_VERSION = "spatial-v10-deskew"

    /**
     * Reads a receipt from the phone's text recognition. The currency comes from what is printed (the phone's region settles
     * shared signs such as $); currencies with no cents are read as whole amounts. Dates follow the phone's region only when
     * the day and month cannot be told apart.
     */
    fun parseOcrResult(rawOcrResult: ReceiptOcrResult, sourceImagePath: String?, locale: java.util.Locale = java.util.Locale.getDefault()): ReceiptSpendDraft {
        val currencyCode = ReceiptCurrencyDetector.detect(rawOcrResult.fullText, locale)
        val wholeUnits = com.example.chatgpt_mealplanner.core.util.MoneyFormat.fractionDigits(currencyCode) == 0
        val input = if (wholeUnits) ReceiptZeroDecimal.normalise(rawOcrResult) else rawOcrResult
        val draft = parseNormalisedOcr(input, sourceImagePath, locale).copy(currencyCode = currencyCode)
        return if (wholeUnits) draft.scaledToWholeUnits() else draft
    }

    private fun parseNormalisedOcr(rawOcrResult: ReceiptOcrResult, sourceImagePath: String?, locale: java.util.Locale): ReceiptSpendDraft {
        val ocrResult = ReceiptDeskew.straighten(rawOcrResult)
        safeLog("[PARSER_ENTRY] Version=$RECEIPT_PARSER_VERSION, Total lines=${ocrResult.lines.size}")

        // 1. ISOLATED RECEIPT SUMMARY PARSER (Independent from product item OCR)
        val summary = ReceiptSummaryParser.parseSummary(ocrResult, locale)
        safeLog("[SUMMARY_RESULT] Retailer=${summary.retailerId}, Paid=${summary.finalPaidTotalMinorPence}, Gross=${summary.grossTotalMinorPence}, Savings=${summary.savingsMinorPence}")

        val items = mutableListOf<ReceiptSpendItemDraft>()
        val rawPriceTokens = mutableMapOf<String, String>()

        var expectedItemCountFromReceipt: Int? = null
        var taxSummaryStartY = Int.MAX_VALUE
        var paymentRegionStartY = Int.MAX_VALUE

        ocrResult.lines.forEach { line ->
            val norm = ReceiptDescriptionNormalizer.normalize(line.text)
            if (norm.contains("TAX SUMMARY")) {
                if (line.top > 0 && line.top < taxSummaryStartY) {
                    taxSummaryStartY = line.top
                }
            }
            if (isHardExclusionLine(norm) && !norm.contains("TAX SUMMARY")) {
                if (line.top > 0 && line.top < paymentRegionStartY) {
                    paymentRegionStartY = line.top
                }
            }

            if (norm.contains("ITEMS SOLD") || norm.contains("NO. ITEMS")) {
                val match = Regex("([0-9]+)$").find(norm)
                if (match != null) {
                    expectedItemCountFromReceipt = match.groupValues[1].toIntOrNull()
                }
            }
        }

        val hardExclusionLimitY = minOf(taxSummaryStartY, paymentRegionStartY)

        // 2. Spatial Two-Column Product Line Reconstruction Engine
        val alignment = ReceiptRowAligner.align(ocrResult.lines)
        val consumedPriceLines = mutableSetOf<Int>()
        val consumedDescriptionLines = mutableSetOf<Int>()

        val tillCodeRowCandidates = mutableListOf<TillCodeRowCandidate>()
        val subLines = mutableListOf<Pair<Int, ReceiptSubLine>>()
        val itemLineIndexes = mutableMapOf<String, Int>()
        var unpricedDescription: Pair<Int, ReceiptOcrLine>? = null

        ocrResult.lines.forEachIndexed { lineIdx, ocrLine ->
            if (lineIdx in consumedDescriptionLines || lineIdx in consumedPriceLines) {
                return@forEachIndexed
            }

            if (ocrLine.centerY >= hardExclusionLimitY && hardExclusionLimitY != Int.MAX_VALUE) {
                safeLog("[ITEM_REJECTED] Line $lineIdx \"${ocrLine.text}\" REJECTED: EXCLUDED_REGION_Y (y=${ocrLine.centerY} >= $hardExclusionLimitY)")
                return@forEachIndexed
            }

            val normLine = ReceiptDescriptionNormalizer.normalize(ocrLine.text)
            if (normLine.isBlank()) return@forEachIndexed

            if (isPurchaseRegionEnd(normLine) || isHardExclusionLine(normLine)) {
                safeLog("[ITEM_REJECTED] Line $lineIdx \"${ocrLine.text}\" REJECTED: REGION_END_OR_EXCLUSION_KEYWORD")
                return@forEachIndexed
            }

            val subLine = ReceiptSubLineParser.parse(ocrLine.text)
            if (subLine != null) {
                // A detail row printed above its product has that product's price on the next row.
                val described = unpricedDescription?.takeUnless {
                    nextRowPrice(ocrResult.lines, alignment, lineIdx) == subLine.linePricePence
                }
                if (described != null) {
                    // The product's only price is on this detail row.
                    val normDesc = ReceiptDescriptionNormalizer.normalize(described.second.text)
                    val item = ReceiptSpendItemDraft(
                        rawDescription = described.second.text,
                        normalizedDescription = normDesc,
                        lineAmountMinorPence = subLine.linePricePence,
                        quantity = subLine.quantity,
                        unitPriceMinorPence = subLine.unitPricePence ?: subLine.linePricePence,
                        category = ReceiptSpendCategory.classifyDescription(normDesc).name,
                        isDiscount = false
                    )
                    itemLineIndexes[item.id] = described.first
                    items.add(item)
                    unpricedDescription = null
                } else {
                    subLines += lineIdx to subLine
                }
                safeLog("[SUB_LINE] Line $lineIdx \"${ocrLine.text}\" quantity=${subLine.quantity}, line=${subLine.linePricePence}p")
                return@forEachIndexed
            }
            if (!ReceiptRowAligner.isPriceOnly(ocrLine.text)) unpricedDescription = null

            if (isFusedWithTillCodes(normLine)) {
                val candidate = tillCodeRowCandidate(lineIdx, ocrLine)
                if (candidate != null) tillCodeRowCandidates.add(candidate)
                safeLog("[ITEM_REJECTED] Line $lineIdx \"${ocrLine.text}\" REJECTED: FUSED_WITH_TILL_CODES, recoverable=${candidate != null}")
                return@forEachIndexed
            }

            if (ReceiptDescriptionNormalizer.isNonProductLine(normLine) && !normLine.contains("SAVING") && !normLine.contains("DISCOUNT") && !ReceiptVocabulary.isDiscountLine(normLine)) {
                safeLog("[ITEM_REJECTED] Line $lineIdx \"${ocrLine.text}\" REJECTED: NON_PRODUCT_KEYWORD")
                return@forEachIndexed
            }

            // A price alone can sit above its description in a tilted photo. Leave it for that
            // description unless a higher-confidence neighbour needs it (handled below).
            if (lineIdx in alignment.priceOnlyLines && lineIdx in alignment.priceLineFor.values) {
                return@forEachIndexed
            }

            // Case A: Price is on the same line
            val elems = if (ocrLine.elements.isNotEmpty()) ocrLine.elements.sortedBy { it.left } else listOf(ReceiptOcrElement(ocrLine.text, ocrLine.left, ocrLine.top, ocrLine.right, ocrLine.bottom, ocrLine.centerY))
            val lastElem = elems.lastOrNull() ?: return@forEachIndexed

            var directPricePence = ReceiptMoneyParser.parseRightAlignedAmountPence(lastElem.text)
            if (directPricePence == null && elems.size > 1) {
                directPricePence = ReceiptMoneyParser.parseRightAlignedAmountPence(ocrLine.text)
            }

            var matchedPricePence: Long? = directPricePence
            var rawDescString = ocrLine.text
            var rawPriceToken = lastElem.text
            var isPreferredDescription = false

            if (directPricePence != null) {
                // Check if an unconsumed clean left-column description line exists (e.g. "ASDA HAM" adjacent to "adno nolemaoinie g4.00")
                val cleanLeftCandidate = ocrResult.lines.mapIndexed { idx, candidate -> Pair(idx, candidate) }.firstOrNull { (idx, candidate) ->
                    idx !in consumedDescriptionLines &&
                    idx !in consumedPriceLines &&
                    idx != lineIdx &&
                    candidate.centerY < hardExclusionLimitY &&
                    Math.abs(candidate.centerY - ocrLine.centerY) <= maxOf(18, alignment.rowTolerance) &&
                    candidate.left < ocrLine.left &&
                    candidate.confidence > ocrLine.confidence &&
                    ReceiptDescriptionNormalizer.normalize(candidate.text).contains(Regex("[A-Za-z]")) &&
                    !ReceiptDescriptionNormalizer.isNonProductLine(ReceiptDescriptionNormalizer.normalize(candidate.text))
                }

                if (cleanLeftCandidate != null) {
                    consumedDescriptionLines.add(cleanLeftCandidate.first)
                    consumedPriceLines.add(lineIdx)
                    consumedDescriptionLines.add(lineIdx)
                    rawDescString = cleanLeftCandidate.second.text
                    isPreferredDescription = true
                    safeLog("[CLEAN_DESCRIPTION_PREFERRED] Replaced corrupted description \"${ocrLine.text}\" with clean description \"${cleanLeftCandidate.second.text}\" for price ${directPricePence}p")
                } else {
                    consumedDescriptionLines.add(lineIdx)
                    consumedPriceLines.add(lineIdx)
                }
            } else {
                // Case B: Price is on a separate line spatially aligned on the right (Two-Column Pairing)
                val candidatePriceLine = alignment.priceLineFor[lineIdx]
                    ?.takeIf { it !in consumedPriceLines && ocrResult.lines[it].centerY < hardExclusionLimitY }
                    ?.let { Pair(it, ocrResult.lines[it]) }

                if (candidatePriceLine != null) {
                    consumedPriceLines.add(candidatePriceLine.first)
                    consumedDescriptionLines.add(lineIdx)
                    matchedPricePence = ReceiptMoneyParser.parseRightAlignedAmountPence(candidatePriceLine.second.text)
                    rawPriceToken = candidatePriceLine.second.text
                    rawDescString = ocrLine.text
                    safeLog("[SPATIAL_PAIR_SUCCESS] Paired Line $lineIdx \"${ocrLine.text}\" with Line ${candidatePriceLine.first} \"${candidatePriceLine.second.text}\" -> price=${matchedPricePence}p")
                }
            }

            val pricePence = matchedPricePence
            if (pricePence == null) {
                safeLog("[ITEM_REJECTED] Line $lineIdx \"${ocrLine.text}\" REJECTED: NO_PRICE_CANDIDATE")
                if (normLine.count { it.isLetter() } >= 2) unpricedDescription = lineIdx to ocrLine
                return@forEachIndexed
            }

            val isDiscountLine = pricePence < 0 || normLine.contains("SAVING") || normLine.contains("DISCOUNT") || normLine.contains("MULTIBUY") || normLine.contains("PROMO") ||
                normLine.contains("MARKDOWN") || normLine.contains("MARK DOWN") || normLine.contains("PRICE OVERRIDE") ||
                ReceiptVocabulary.isDiscountLine(normLine)
            val amountPence = if (isDiscountLine && pricePence > 0) -pricePence else pricePence

            if (isDiscountLine) {
                safeLog("[ITEM_DISCOUNT] Line $lineIdx \"${ocrLine.text}\" tracked as discount line")
                return@forEachIndexed
            }

            // Description = ALL elements to the left of the price element
            val descElements = if (elems.size > 1 && ReceiptMoneyParser.parseAmountPence(lastElem.text) != null) {
                elems.dropLast(1)
            } else {
                elems
            }

            var cleanDesc = if (isPreferredDescription) {
                rawDescString
            } else if (descElements.isNotEmpty()) {
                descElements.joinToString(" ") { it.text }.trim()
            } else {
                rawDescString
            }

            cleanDesc = cleanDesc.replace(Regex("""-?\s*[£$€]?\s*(?<![A-Za-z])[CLegqPS]?[0-9]+[.,][0-9]{2}(?:\s+[A-Z*])?\s*$""", RegexOption.IGNORE_CASE), "").trim()
            // A till code printed before the name ("81008025 Four Loko Pink") is not part of it.
            cleanDesc = cleanDesc.replace(Regex("""^\d{6,}\s+"""), "").trim()
            val normDesc = ReceiptDescriptionNormalizer.normalize(cleanDesc)

            if (normDesc.isBlank() || !normDesc.contains(Regex("[A-Za-z]")) || normDesc.length < 2 || ReceiptDescriptionNormalizer.isNonProductLine(normDesc)) {
                safeLog("[ITEM_REJECTED] Line $lineIdx \"${ocrLine.text}\" REJECTED: INVALID_DESCRIPTION (\"$normDesc\")")
                return@forEachIndexed
            }

            val category = ReceiptSpendCategory.classifyDescription(normDesc).name

            safeLog("[ITEM_ACCEPTED] Line $lineIdx -> desc=\"$normDesc\", amount=${amountPence}p, category=$category")

            val newItem = ReceiptSpendItemDraft(
                rawDescription = rawDescString,
                normalizedDescription = normDesc,
                lineAmountMinorPence = amountPence,
                quantity = 1,
                unitPriceMinorPence = amountPence,
                category = category,
                isDiscount = false
            )
            rawPriceTokens[newItem.id] = rawPriceToken
            itemLineIndexes[newItem.id] = lineIdx
            items.add(newItem)
        }

        var recognisedGrossItemSpend = items.sumOf { it.lineAmountMinorPence }
        val finalPaidTotal = summary.finalPaidTotalMinorPence

        val grossTarget: Long? = when {
            summary.grossTotalMinorPence != null && summary.grossTotalMinorPence > 0L -> summary.grossTotalMinorPence
            finalPaidTotal != null && finalPaidTotal > 0L && summary.savingsMinorPence > 0L -> finalPaidTotal + summary.savingsMinorPence
            finalPaidTotal != null && finalPaidTotal > 0L -> finalPaidTotal
            else -> null
        }

        // A row fused with till codes only counts if its corrected price makes the receipt add up.
        if (grossTarget != null) {
            tillCodeRowCandidates.forEach { candidate ->
                val normDesc = ReceiptDescriptionNormalizer.normalize(candidate.description)
                val recovered = ReceiptSpendItemDraft(
                    rawDescription = candidate.rawText,
                    normalizedDescription = normDesc,
                    lineAmountMinorPence = candidate.recoveredPence,
                    quantity = 1,
                    unitPriceMinorPence = candidate.recoveredPence,
                    category = ReceiptSpendCategory.classifyDescription(normDesc).name,
                    isDiscount = false
                )
                val withCandidate = items + recovered
                val sum = withCandidate.sumOf { it.lineAmountMinorPence }
                val addsUp = sum == grossTarget ||
                    (sum > grossTarget && correctMisreadCurrencySymbols(withCandidate, rawPriceTokens, grossTarget) != null)
                if (addsUp) {
                    items.add(recovered)
                    recognisedGrossItemSpend = sum
                    safeLog("[RECOVERED_ITEM] description=\"$normDesc\", amount=${candidate.recoveredPence}, sourceLine=${candidate.lineIndex}")
                }
            }
        }

        safeLog("[RECONCILIATION_BEFORE] itemCount=${items.size}, sum=${recognisedGrossItemSpend}p, grossTarget=$grossTarget, delta=${recognisedGrossItemSpend - (grossTarget ?: 0L)}p")

        if (grossTarget != null && recognisedGrossItemSpend > grossTarget) {
            val corrected = correctMisreadCurrencySymbols(items, rawPriceTokens, grossTarget)
            if (corrected != null) {
                items.clear()
                items.addAll(corrected)
                recognisedGrossItemSpend = items.sumOf { it.lineAmountMinorPence }
                safeLog("[PRICE_RECOVERY_APPLIED] sum=$recognisedGrossItemSpend grossTarget=$grossTarget")
            }
        }

        applySubLineQuantities(items, itemLineIndexes, subLines)

        val review = evaluateReceiptReview(
            retailerId = summary.retailerId,
            purchasedAt = summary.purchasedAt,
            dateNeedsConfirmation = summary.dateNeedsConfirmation,
            paidTotalPence = finalPaidTotal,
            grossTargetPence = grossTarget,
            savingsPence = summary.savingsMinorPence,
            recognisedGrossPence = recognisedGrossItemSpend,
            itemCount = items.sumOf { it.quantity },
            expectedItemCount = expectedItemCountFromReceipt,
            summaryArithmeticValid = summary.summaryArithmeticValid
        )
        val unrecognisedGross = review.unrecognisedGrossPence
        val recognisedGrossOverage = review.overagePence
        val discrepancy = review.discrepancyPence
        val isReconciled = review.isReconciled
        val needsReview = review.needsReview
        val canSave = review.canSave
        val reviewWarningText = review.warning
        val fingerprint = receiptFingerprint(summary.retailerId, summary.purchasedAt, finalPaidTotal, summary.transactionRef)

        safeLog("[FINAL_RECEIPT_RESULT] parserVersion=$RECEIPT_PARSER_VERSION, itemCount=${items.size}, recognisedGross=$recognisedGrossItemSpend, grossTarget=$grossTarget, savings=${summary.savingsMinorPence}, finalPaid=$finalPaidTotal, overage=$recognisedGrossOverage, unrecognisedGross=$unrecognisedGross, isReconciled=$isReconciled, canSave=$canSave")
        items.forEachIndexed { idx, item ->
            safeLog("[FINAL_ITEM] index=$idx, desc=\"${item.normalizedDescription}\", amount=${item.lineAmountMinorPence}p, category=${item.category}")
        }

        return ReceiptSpendDraft(
            retailerId = summary.retailerId,
            purchasedAt = summary.purchasedAt,
            dateNeedsConfirmation = summary.dateNeedsConfirmation,
            totalMinorPence = finalPaidTotal,
            explicitGrossTotalMinorPence = summary.grossTotalMinorPence ?: grossTarget,
            explicitSavingsMinorPence = summary.savingsMinorPence,
            explicitFinalPaidMinorPence = summary.finalPaidTotalMinorPence,
            recognisedGrossItemSpendMinorPence = recognisedGrossItemSpend,
            unrecognisedGrossSpendMinorPence = unrecognisedGross,
            recognisedGrossOverageMinorPence = recognisedGrossOverage,
            calculatedItemsSumMinorPence = recognisedGrossItemSpend,
            discountMinorPence = summary.savingsMinorPence,
            itemCount = items.size,
            expectedItemCount = expectedItemCountFromReceipt,
            discrepancyMinorPence = discrepancy,
            summaryArithmeticValid = summary.summaryArithmeticValid,
            isReconciled = isReconciled,
            needsReview = needsReview,
            canSave = canSave,
            reviewWarning = reviewWarningText,
            fingerprint = fingerprint,
            sourceImagePath = sourceImagePath,
            transactionRef = summary.transactionRef,
            items = items
        )
    }

    /**
     * OCR can read a pound sign as a digit, so "£2.40" becomes "22.40" or "12.40". A price with no
     * currency symbol may have one leading digit dropped, but only if exactly one combination of
     * those corrections makes the items equal the receipt total. Otherwise nothing changes and the
     * mismatch stays visible for the user to review.
     */
    private fun correctMisreadCurrencySymbols(
        items: List<ReceiptSpendItemDraft>,
        rawPriceTokens: Map<String, String>,
        grossTarget: Long
    ): List<ReceiptSpendItemDraft>? {
        val candidates = items.mapNotNull { item ->
            val corrected = withoutLeadingDigit(rawPriceTokens[item.id].orEmpty()) ?: return@mapNotNull null
            item to corrected
        }
        if (candidates.isEmpty() || candidates.size > 12) return null

        val total = items.sumOf { it.lineAmountMinorPence }
        val solutions = (1 until (1 shl candidates.size)).filter { mask ->
            val saved = candidates.indices.filter { mask and (1 shl it) != 0 }
                .sumOf { candidates[it].first.lineAmountMinorPence - candidates[it].second }
            total - saved == grossTarget
        }
        val mask = solutions.singleOrNull() ?: return null
        val replacements = candidates.indices.filter { mask and (1 shl it) != 0 }
            .associate { candidates[it].first.id to candidates[it].second }
        return items.map { item ->
            replacements[item.id]?.let { item.copy(lineAmountMinorPence = it, unitPriceMinorPence = it) } ?: item
        }
    }

    private fun nextRowPrice(lines: List<ReceiptOcrLine>, alignment: ReceiptRowAlignment, afterIndex: Int): Long? {
        val next = (afterIndex + 1 until lines.size).firstOrNull { !ReceiptRowAligner.isPriceOnly(lines[it].text) } ?: return null
        return ReceiptMoneyParser.parseRightAlignedAmountPence(lines[next].text)
            ?: alignment.priceLineFor[next]?.let { ReceiptMoneyParser.parseRightAlignedAmountPence(lines[it].text) }
    }

    /**
     * Gives a product its quantity from the detail row next to it, preferring the row above. The
     * prices have to agree, so a detail row can't change an unrelated product. A row that fits
     * nothing adds nothing, and any mismatch stays visible in the review.
     */
    private fun applySubLineQuantities(
        items: MutableList<ReceiptSpendItemDraft>,
        itemLineIndexes: Map<String, Int>,
        subLines: List<Pair<Int, ReceiptSubLine>>
    ) {
        subLines.forEach { (lineIndex, subLine) ->
            val ordered = items.indices.sortedBy { itemLineIndexes[items[it].id] ?: Int.MAX_VALUE }
            val above = ordered.lastOrNull { (itemLineIndexes[items[it].id] ?: Int.MAX_VALUE) < lineIndex }
            val below = ordered.firstOrNull { (itemLineIndexes[items[it].id] ?: -1) > lineIndex }
            val target = listOfNotNull(above, below).firstOrNull {
                items[it].quantity == 1 && items[it].lineAmountMinorPence == subLine.linePricePence
            } ?: return@forEach
            items[target] = items[target].copy(
                quantity = subLine.quantity,
                unitPriceMinorPence = subLine.unitPricePence ?: items[target].unitPriceMinorPence
            )
        }
    }

    private class TillCodeRowCandidate(
        val lineIndex: Int,
        val rawText: String,
        val description: String,
        val recoveredPence: Long
    )

    private val digitInsideLetters = Regex("""[A-Z][0-9]+[A-Z]""")

    /**
     * A blurred row can merge with the till codes printed beside it, as in "WEDGES0N03HORE J3GH 92.40".
     * One odd word can be a misread product (BR0CC0LI), so it takes two.
     */
    private fun isFusedWithTillCodes(normText: String): Boolean =
        normText.split(' ').count { digitInsideLetters.containsMatchIn(it) } >= 2

    /** Only a low-confidence row can be recovered: its name is the text before the first digit, its price without the misread pound sign. */
    private fun tillCodeRowCandidate(lineIndex: Int, line: ReceiptOcrLine): TillCodeRowCandidate? {
        if (line.confidence >= 0.6f) return null
        val recoveredPence = withoutLeadingDigit(line.text.trim().substringAfterLast(' ')) ?: return null
        val description = line.text.takeWhile { !it.isDigit() }.trim()
        if (description.count { it.isLetter() } < 2) return null
        return TillCodeRowCandidate(lineIndex, line.text, description, recoveredPence)
    }

    private val unsymbolisedPrice = Regex("""^(\d)(\d{1,2})[.,](\d{2})$""")

    /** The price if a pound sign was misread as its first digit: "92.40" gives 240p. */
    private fun withoutLeadingDigit(token: String): Long? {
        val match = unsymbolisedPrice.find(token.trim()) ?: return null
        val (_, pounds, pence) = match.destructured
        return pounds.toLong() * 100L + pence.toLong()
    }

    private fun safeLog(msg: String) {
        if (!com.example.chatgpt_mealplanner.BuildConfig.DEBUG) return
        try {
            Log.d("RECEIPT_PIPELINE_TRACE", msg)
        } catch (ignored: Throwable) {}
    }

    private fun isPurchaseRegionEnd(normText: String): Boolean {
        val lower = normText.lowercase()
        return lower.startsWith("subtotal") ||
                lower.startsWith("sub total") ||
                lower == "total" || lower.startsWith("total ") || lower.startsWith("total:") ||
                lower.contains("total savings") ||
                lower.contains("no. items") ||
                lower.contains("asda rewards") ||
                lower.contains("tax summary") ||
                lower.startsWith("card") ||
                lower.contains("balance due") ||
                ReceiptVocabulary.startsWithTotal(lower)
    }

    private fun isHardExclusionLine(normText: String): Boolean {
        val lower = normText.lowercase()
        return lower.contains("tax summary") ||
                lower == "card" || lower.startsWith("card ") ||
                lower.contains("visa") ||
                lower.contains("mastercard") ||
                lower.contains("tendered") ||
                lower.contains("total to pay") ||
                ReceiptVocabulary.isPaymentLine(lower) ||
                lower.contains("contactless") ||
                lower.contains("payment approved") ||
                lower.contains("pan seq") ||
                lower.contains("auth code") ||
                lower.contains("trx id")
    }
}
