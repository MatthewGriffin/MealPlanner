package com.example.chatgpt_mealplanner.core.barcode.util

import com.example.chatgpt_mealplanner.core.model.QuantityUnit

data class ParsedProductInfo(
    val cleanName: String,
    val extractedPackCount: Int? = null,
    val extractedPackSize: Double? = null,
    val extractedPackSizeUnit: QuantityUnit? = null
)

object BarcodeNormalizer {

    fun normalize(rawBarcode: String?): String? {
        if (rawBarcode.isNullOrBlank()) return null
        val cleaned = rawBarcode.trim().replace(Regex("[^0-9A-Za-z]"), "")
        if (cleaned.isBlank()) return null
        return if (cleaned.all { it.isDigit() } && cleaned.length in 8..14) {
            cleaned.trimStart('0').ifBlank { "0" }
        } else {
            cleaned.uppercase()
        }
    }

    fun formatForExternalLookup(rawBarcode: String?): String? {
        if (rawBarcode.isNullOrBlank()) return null
        val cleaned = rawBarcode.trim().replace(Regex("[^0-9A-Za-z]"), "")
        if (cleaned.isBlank()) return null
        return if (cleaned.all { it.isDigit() } && cleaned.length in 8..14) {
            cleaned // Preserves significant leading zeros for external API lookups
        } else {
            cleaned.uppercase()
        }
    }

    fun isValidGroceryBarcode(barcode: String?): Boolean {
        val normalized = normalize(barcode) ?: return false
        return normalized.length in 1..14 && normalized.all { it.isDigit() }
    }

    fun isVariableWeightOrInternalBarcode(rawBarcode: String?): Boolean {
        val formatted = formatForExternalLookup(rawBarcode) ?: return false
        if (formatted.length != 13 || !formatted.all { it.isDigit() }) return false
        val prefix2 = formatted.substring(0, 2)
        val prefix2Int = prefix2.toIntOrNull() ?: return false
        return prefix2 == "02" || prefix2 == "04" || (prefix2Int in 20..29)
    }

    fun parseProductTitle(rawTitle: String): ParsedProductInfo {
        val title = rawTitle.trim()
        if (title.isBlank()) return ParsedProductInfo(rawTitle)

        // Rule 1: Guard against brand names with numbers like 7UP, 3M, 2in1, 5 Star, 100%
        val lowercase = title.lowercase()
        if (lowercase.startsWith("7up") ||
            lowercase.startsWith("3m") ||
            lowercase.contains("2in1") || lowercase.contains("3in1") ||
            lowercase.contains("100%")
        ) {
            return ParsedProductInfo(title)
        }

        // Rule 2: Multi-pack format like "6x330ml Coca-Cola" or "2 x 500g Pasta"
        val multipackRegex = Regex("""^(\d+)\s*[xX×]\s*(\d+(?:\.\d+)?)\s*(g|kg|ml|l|cl|pk|pack|pcs|units?)?\s+(.+)""", RegexOption.IGNORE_CASE)
        val multiMatch = multipackRegex.find(title)
        if (multiMatch != null) {
            val count = multiMatch.groupValues[1].toIntOrNull()
            val size = multiMatch.groupValues[2].toDoubleOrNull()
            val unitStr = multiMatch.groupValues[3].lowercase()
            val remainingName = multiMatch.groupValues[4].trim()

            val unit = when (unitStr) {
                "g" -> QuantityUnit.GRAM
                "kg" -> QuantityUnit.KILOGRAM
                "ml" -> QuantityUnit.MILLILITRE
                "l" -> QuantityUnit.LITRE
                else -> QuantityUnit.UNIT
            }
            if (count != null && remainingName.isNotBlank()) {
                return ParsedProductInfo(
                    cleanName = remainingName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    extractedPackCount = count,
                    extractedPackSize = size ?: 1.0,
                    extractedPackSizeUnit = unit
                )
            }
        }

        // Rule 3: Single pack size prefix like "500g Pasta" or "1.5L Milk"
        val singleSizeRegex = Regex("""^(\d+(?:\.\d+)?)\s*(g|kg|ml|l|cl)\s+(.+)""", RegexOption.IGNORE_CASE)
        val sizeMatch = singleSizeRegex.find(title)
        if (sizeMatch != null) {
            val size = sizeMatch.groupValues[1].toDoubleOrNull()
            val unitStr = sizeMatch.groupValues[2].lowercase()
            val remainingName = sizeMatch.groupValues[3].trim()

            val unit = when (unitStr) {
                "g" -> QuantityUnit.GRAM
                "kg" -> QuantityUnit.KILOGRAM
                "ml" -> QuantityUnit.MILLILITRE
                "l" -> QuantityUnit.LITRE
                else -> QuantityUnit.UNIT
            }
            if (size != null && remainingName.isNotBlank()) {
                return ParsedProductInfo(
                    cleanName = remainingName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    extractedPackSize = size,
                    extractedPackSizeUnit = unit
                )
            }
        }

        // Rule 4: Egg / Item count prefix like "10 Free Range Eggs" or "6 Pack English Muffins"
        val countPrefixRegex = Regex("""^(\d+)\s+(?:large\s+|medium\s+|small\s+)?(?:free\s+range\s+)?(pack\s+of|pk|pack|eggs?|pieces?|pcs|cans?|bottles?|sachets?)\b\s*(.*)""", RegexOption.IGNORE_CASE)
        val countMatch = countPrefixRegex.find(title)
        if (countMatch != null) {
            val count = countMatch.groupValues[1].toIntOrNull()
            val indicator = countMatch.groupValues[2].lowercase()
            val remainingName = countMatch.groupValues[3].trim()

            if (count != null) {
                val cleanTitleName = if (indicator.startsWith("egg")) {
                    if (title.lowercase().contains("free range")) "Free Range Eggs" else "Eggs"
                } else if (remainingName.isNotBlank()) {
                    remainingName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                } else {
                    title
                }
                return ParsedProductInfo(
                    cleanName = cleanTitleName,
                    extractedPackSize = count.toDouble(),
                    extractedPackSizeUnit = QuantityUnit.UNIT
                )
            }
        }

        return ParsedProductInfo(title)
    }
}
