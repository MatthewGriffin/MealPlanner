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

/** A quantity without a pointless ".0": 415.0 is "415", 0.5 stays "0.5". */
fun formatQuantityNumber(quantity: Double): String =
    if (quantity % 1.0 == 0.0) quantity.toLong().toString() else quantity.toString()

/** The unit as a picker shows it, in words. */
fun QuantityUnit.pickerLabel(): String = when (this) {
    QuantityUnit.GRAM -> "Grams (g)"
    QuantityUnit.KILOGRAM -> "Kilograms (kg)"
    QuantityUnit.MILLILITRE -> "Millilitres (ml)"
    QuantityUnit.LITRE -> "Litres (l)"
    QuantityUnit.UNIT -> "Items"
    QuantityUnit.PACK -> "Packs"
    QuantityUnit.TIN -> "Tins"
    QuantityUnit.BOTTLE -> "Bottles"
    QuantityUnit.BAG -> "Bags"
    QuantityUnit.SLICE -> "Slices"
    QuantityUnit.PORTION -> "Portions"
}

/** An amount for a card, such as "415 g", "2 items" or "1 tin". */
fun describeAmount(quantity: Double, unit: QuantityUnit?): String {
    val number = formatQuantityNumber(quantity)
    val one = quantity == 1.0
    return when (unit) {
        null, QuantityUnit.UNIT -> if (one) "1 item" else "$number items"
        QuantityUnit.GRAM -> "$number g"
        QuantityUnit.KILOGRAM -> "$number kg"
        QuantityUnit.MILLILITRE -> "$number ml"
        QuantityUnit.LITRE -> "$number l"
        QuantityUnit.PACK -> if (one) "1 pack" else "$number packs"
        QuantityUnit.TIN -> if (one) "1 tin" else "$number tins"
        QuantityUnit.BOTTLE -> if (one) "1 bottle" else "$number bottles"
        QuantityUnit.BAG -> if (one) "1 bag" else "$number bags"
        QuantityUnit.SLICE -> if (one) "1 slice" else "$number slices"
        QuantityUnit.PORTION -> if (one) "1 portion" else "$number portions"
    }
}
