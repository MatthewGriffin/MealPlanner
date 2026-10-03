package com.example.chatgpt_mealplanner.core.barcode.util

import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit

data class ParsedQuantityResult(
    val netQuantity: Double? = null,
    val netQuantityUnit: QuantityUnit? = null,
    val packCount: Int? = null,
    val packSize: Double? = null,
    val packSizeUnit: QuantityUnit? = null,
    val confidence: QuantityConfidence = QuantityConfidence.UNKNOWN
)

object QuantityParser {

    // Regex for Multi-Pack: "4 x 405 g", "6x330ml", "2 * 500g"
    private val multiPackRegex = Regex(
        """(\d+)\s*[xX*]\s*(\d+(?:\.\d+)?)\s*(g|kg|ml|l|litre|litres|gram|grams|kilogram|kilograms)\b""",
        RegexOption.IGNORE_CASE
    )

    // Regex for Single Unit: "400 g", "1.5 kg", "500 ml", "2L"
    private val singleQuantityRegex = Regex(
        """(\d+(?:\.\d+)?)\s*(g|kg|ml|l|litre|litres|gram|grams|kilogram|kilograms|pack|tin|bottle|bag|unit|slice|portion)\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(rawQuantityString: String?): ParsedQuantityResult {
        if (rawQuantityString.isNullOrBlank()) {
            return ParsedQuantityResult(confidence = QuantityConfidence.UNKNOWN)
        }

        val cleaned = rawQuantityString.trim().lowercase()

        // 1. Try Multi-Pack Match
        val multiMatch = multiPackRegex.find(cleaned)
        if (multiMatch != null) {
            val count = multiMatch.groupValues[1].toIntOrNull()
            val size = multiMatch.groupValues[2].toDoubleOrNull()
            val unitStr = multiMatch.groupValues[3]
            val unit = parseUnit(unitStr)

            if (count != null && size != null && unit != null) {
                val totalQty = convertToMetricBase(count * size, unit)
                val packSizeBase = convertToMetricBase(size, unit)
                val baseUnit = getMetricBaseUnit(unit)

                return ParsedQuantityResult(
                    netQuantity = totalQty,
                    netQuantityUnit = baseUnit,
                    packCount = count,
                    packSize = packSizeBase,
                    packSizeUnit = baseUnit,
                    confidence = QuantityConfidence.EXACT
                )
            }
        }

        // 2. Try Single Unit Match
        val singleMatch = singleQuantityRegex.find(cleaned)
        if (singleMatch != null) {
            val value = singleMatch.groupValues[1].toDoubleOrNull()
            val unitStr = singleMatch.groupValues[2]
            val unit = parseUnit(unitStr)

            if (value != null && unit != null) {
                val baseValue = convertToMetricBase(value, unit)
                val baseUnit = getMetricBaseUnit(unit)

                return ParsedQuantityResult(
                    netQuantity = baseValue,
                    netQuantityUnit = baseUnit,
                    confidence = QuantityConfidence.EXACT
                )
            }
        }

        // 3. Ambiguous / Unsupported String -> UNKNOWN without fake values
        return ParsedQuantityResult(confidence = QuantityConfidence.UNKNOWN)
    }

    private fun parseUnit(unitStr: String): QuantityUnit? {
        return when (unitStr.lowercase()) {
            "g", "gram", "grams" -> QuantityUnit.GRAM
            "kg", "kilogram", "kilograms" -> QuantityUnit.KILOGRAM
            "ml", "millilitre", "millilitres" -> QuantityUnit.MILLILITRE
            "l", "litre", "litres" -> QuantityUnit.LITRE
            "pack" -> QuantityUnit.PACK
            "tin" -> QuantityUnit.TIN
            "bottle" -> QuantityUnit.BOTTLE
            "bag" -> QuantityUnit.BAG
            "unit" -> QuantityUnit.UNIT
            "slice" -> QuantityUnit.SLICE
            "portion" -> QuantityUnit.PORTION
            else -> null
        }
    }

    private fun convertToMetricBase(value: Double, unit: QuantityUnit): Double {
        return when (unit) {
            QuantityUnit.KILOGRAM -> value * 1000.0
            QuantityUnit.LITRE -> value * 1000.0
            else -> value
        }
    }

    private fun getMetricBaseUnit(unit: QuantityUnit): QuantityUnit {
        return when (unit) {
            QuantityUnit.KILOGRAM -> QuantityUnit.GRAM
            QuantityUnit.LITRE -> QuantityUnit.MILLILITRE
            else -> unit
        }
    }
}
