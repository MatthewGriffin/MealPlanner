package com.example.chatgpt_mealplanner.core.util

import com.example.chatgpt_mealplanner.core.model.QuantityUnit

fun formatQuantityAndUnit(quantity: Double?, unit: QuantityUnit?): String {
    if (quantity == null) return ""
    val qtyFormatted = if (quantity % 1.0 == 0.0) {
        quantity.toInt().toString()
    } else {
        quantity.toString()
    }
    val unitStr = when (unit) {
        QuantityUnit.GRAM -> "g"
        QuantityUnit.KILOGRAM -> "kg"
        QuantityUnit.MILLILITRE -> "ml"
        QuantityUnit.LITRE -> "l"
        QuantityUnit.UNIT -> ""
        QuantityUnit.PACK -> "pack"
        QuantityUnit.TIN -> "tin"
        QuantityUnit.BOTTLE -> "bottle"
        QuantityUnit.BAG -> "bag"
        QuantityUnit.SLICE -> "slice"
        QuantityUnit.PORTION -> "portion"
        null -> ""
    }
    return if (unitStr.isBlank()) qtyFormatted else "$qtyFormatted $unitStr"
}
