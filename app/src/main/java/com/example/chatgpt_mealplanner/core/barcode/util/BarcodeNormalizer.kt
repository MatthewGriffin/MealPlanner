package com.example.chatgpt_mealplanner.core.barcode.util

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
}
