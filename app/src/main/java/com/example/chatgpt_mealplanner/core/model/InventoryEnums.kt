package com.example.chatgpt_mealplanner.core.model

enum class FoodCategory {
    CUPBOARD,
    FRIDGE,
    FREEZER,
    FRESH_PANTRY,
    BREAD_BAKERY,
    SAUCES_SEASONINGS,
    SNACKS,
    MEAL_KITS,
    OTHER
}

enum class StorageLocation {
    CUPBOARD,
    FRIDGE,
    FREEZER,
    FRESH_PANTRY,
    BREAD_BAKERY,
    SAUCES_SEASONINGS,
    SNACKS,
    MEAL_KITS,
    OTHER
}

enum class QuantityUnit {
    GRAM,
    KILOGRAM,
    MILLILITRE,
    LITRE,
    UNIT,
    PACK,
    TIN,
    BOTTLE,
    BAG,
    SLICE,
    PORTION
}

fun parseQuantityUnit(rawUnit: String?): QuantityUnit {
    if (rawUnit.isNullOrBlank()) return QuantityUnit.UNIT
    val normalized = rawUnit.trim().uppercase()
    return when (normalized) {
        "GRAM", "GRAMS", "G" -> QuantityUnit.GRAM
        "KILOGRAM", "KILOGRAMS", "KG" -> QuantityUnit.KILOGRAM
        "MILLILITRE", "MILLILITRES", "ML" -> QuantityUnit.MILLILITRE
        "LITRE", "LITRES", "L" -> QuantityUnit.LITRE
        "UNIT", "UNITS", "COUNT", "EACH", "ITEM", "ITEMS", "PIECE", "PIECES", "PCS" -> QuantityUnit.UNIT
        "PACK", "PACKS", "PACKAGE", "PACKAGES" -> QuantityUnit.PACK
        "TIN", "TINS", "CAN", "CANS" -> QuantityUnit.TIN
        "BOTTLE", "BOTTLES" -> QuantityUnit.BOTTLE
        "BAG", "BAGS" -> QuantityUnit.BAG
        "SLICE", "SLICES" -> QuantityUnit.SLICE
        "PORTION", "PORTIONS" -> QuantityUnit.PORTION
        else -> try {
            QuantityUnit.valueOf(normalized)
        } catch (_: Exception) {
            QuantityUnit.UNIT
        }
    }
}

enum class QuantityConfidence {
    EXACT,
    APPROXIMATE,
    UNKNOWN
}

enum class ItemStatus {
    AVAILABLE,
    LOW,
    USE_SOON,
    OUT,
    DISCARDED,
    NEEDS_CONFIRMATION
}

enum class TransactionType {
    PURCHASE,
    CONSUMED,
    COOKED,
    DISCARDED,
    RESTOCK,
    USER_CORRECTION,
    ADJUSTMENT
}
