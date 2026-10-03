package com.example.chatgpt_mealplanner.core.receipt

import com.example.chatgpt_mealplanner.core.barcode.util.QuantityParser
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import java.security.MessageDigest
import java.util.UUID

enum class ReceiptLineStatus {
    MATCHED,
    NEEDS_CONFIRMATION,
    IGNORED,
    NON_PRODUCT,
    INVALID
}

enum class ReceiptLineConfidence {
    CONFIRMED,
    HIGH,
    NEEDS_CONFIRMATION
}

data class ReceiptLineDraft(
    val id: String = UUID.randomUUID().toString(),
    val rawText: String,
    val normalizedText: String,
    val displayName: String,
    val quantityPurchased: Int = 1,
    val unitPriceMinorPence: Long? = null,
    val lineTotalMinorPence: Long? = null,
    val candidateCanonicalIngredientId: String? = null,
    val netQuantity: Double? = null,
    val netQuantityUnit: QuantityUnit? = null,
    val packCount: Int? = null,
    val category: FoodCategory = FoodCategory.CUPBOARD,
    val storageLocation: StorageLocation = StorageLocation.CUPBOARD,
    val confidence: ReceiptLineConfidence = ReceiptLineConfidence.NEEDS_CONFIRMATION,
    val status: ReceiptLineStatus = ReceiptLineStatus.NEEDS_CONFIRMATION
)

data class ReceiptImportDraft(
    val id: String = UUID.randomUUID().toString(),
    val retailerId: String,
    val purchasedAt: String,
    val totalAmountMinorPence: Long?,
    val itemCount: Int,
    val fingerprint: String,
    val sourceImagePath: String?,
    val lines: List<ReceiptLineDraft>
)

interface ReceiptParser {
    fun canParse(ocrResult: ReceiptOcrResult): Boolean
    fun parse(ocrResult: ReceiptOcrResult, sourceImagePath: String?): ReceiptImportDraft
}

class GenericReceiptParser : ReceiptParser {

    override fun canParse(ocrResult: ReceiptOcrResult): Boolean = true

    override fun parse(ocrResult: ReceiptOcrResult, sourceImagePath: String?): ReceiptImportDraft {
        val lines = mutableListOf<ReceiptLineDraft>()
        var parsedTotalPence: Long? = null

        ocrResult.lines.forEach { ocrLine ->
            val norm = ReceiptDescriptionNormalizer.normalize(ocrLine.text)
            if (norm.isBlank()) return@forEach

            if (ReceiptDescriptionNormalizer.isNonProductLine(norm)) {
                // Parse total if present
                if (norm.contains("TOTAL") && !norm.contains("SUBTOTAL")) {
                    val priceMatch = Regex("£?\\s*([0-9]+\\.[0-9]{2})").find(ocrLine.text)
                    if (priceMatch != null) {
                        val pounds = priceMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                        parsedTotalPence = (pounds * 100).toLong()
                    }
                }
                lines.add(
                    ReceiptLineDraft(
                        rawText = ocrLine.text,
                        normalizedText = norm,
                        displayName = norm,
                        confidence = ReceiptLineConfidence.CONFIRMED,
                        status = ReceiptLineStatus.NON_PRODUCT
                    )
                )
                return@forEach
            }

            // Extract price at end of line (e.g. "CHKN BRST 600G £3.50")
            val priceMatch = Regex("£?\\s*([0-9]+\\.[0-9]{2})$").find(ocrLine.text)
            val lineTotalPence = priceMatch?.let {
                val pounds = it.groupValues[1].toDoubleOrNull() ?: 0.0
                (pounds * 100).toLong()
            }
            val textWithoutPrice = if (priceMatch != null) ocrLine.text.substring(0, priceMatch.range.first).trim() else ocrLine.text

            // Quantity parsing (e.g. "2 X 400G" or "6 PACK")
            val qtyMatch = Regex("^([0-9]+)\\s*[xX@]\\s*").find(textWithoutPrice)
            val purchasedQty = qtyMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val cleanDescription = if (qtyMatch != null) textWithoutPrice.substring(qtyMatch.range.last + 1).trim() else textWithoutPrice

            val qtyResult = QuantityParser.parse(cleanDescription)

            lines.add(
                ReceiptLineDraft(
                    rawText = ocrLine.text,
                    normalizedText = ReceiptDescriptionNormalizer.normalize(cleanDescription),
                    displayName = cleanDescription,
                    quantityPurchased = purchasedQty,
                    unitPriceMinorPence = if (purchasedQty > 1 && lineTotalPence != null) lineTotalPence / purchasedQty else lineTotalPence,
                    lineTotalMinorPence = lineTotalPence,
                    netQuantity = qtyResult.netQuantity,
                    netQuantityUnit = qtyResult.netQuantityUnit,
                    confidence = if (qtyResult.netQuantity != null) ReceiptLineConfidence.HIGH else ReceiptLineConfidence.NEEDS_CONFIRMATION,
                    status = ReceiptLineStatus.NEEDS_CONFIRMATION
                )
            )
        }

        val productLines = lines.filter { it.status != ReceiptLineStatus.NON_PRODUCT }
        val lineSignature = productLines.joinToString("|") { "${it.normalizedText}:${it.quantityPurchased}:${it.lineTotalMinorPence ?: 0}" }
        val rawFingerprint = "GENERIC_${parsedTotalPence ?: 0}_${productLines.size}_$lineSignature"
        val fingerprint = calculateSha256(rawFingerprint)

        return ReceiptImportDraft(
            retailerId = "GENERIC",
            purchasedAt = "2026-09-27",
            totalAmountMinorPence = parsedTotalPence,
            itemCount = productLines.size,
            fingerprint = fingerprint,
            sourceImagePath = sourceImagePath,
            lines = lines
        )
    }

    private fun calculateSha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

class AsdaReceiptParser : ReceiptParser {
    private val genericParser = GenericReceiptParser()

    override fun canParse(ocrResult: ReceiptOcrResult): Boolean {
        return ocrResult.fullText.contains("ASDA", ignoreCase = true) || ocrResult.fullText.contains("WALMART", ignoreCase = true)
    }

    override fun parse(ocrResult: ReceiptOcrResult, sourceImagePath: String?): ReceiptImportDraft {
        val draft = genericParser.parse(ocrResult, sourceImagePath)
        return draft.copy(retailerId = "ASDA")
    }
}

class IcelandReceiptParser : ReceiptParser {
    private val genericParser = GenericReceiptParser()

    override fun canParse(ocrResult: ReceiptOcrResult): Boolean {
        return ocrResult.fullText.contains("ICELAND", ignoreCase = true)
    }

    override fun parse(ocrResult: ReceiptOcrResult, sourceImagePath: String?): ReceiptImportDraft {
        val draft = genericParser.parse(ocrResult, sourceImagePath)
        return draft.copy(retailerId = "ICELAND")
    }
}

class FoodWarehouseReceiptParser : ReceiptParser {
    private val genericParser = GenericReceiptParser()

    override fun canParse(ocrResult: ReceiptOcrResult): Boolean {
        return ocrResult.fullText.contains("FOOD WAREHOUSE", ignoreCase = true)
    }

    override fun parse(ocrResult: ReceiptOcrResult, sourceImagePath: String?): ReceiptImportDraft {
        val draft = genericParser.parse(ocrResult, sourceImagePath)
        return draft.copy(retailerId = "FOOD_WAREHOUSE")
    }
}
