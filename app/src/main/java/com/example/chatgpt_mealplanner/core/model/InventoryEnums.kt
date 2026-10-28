package com.example.chatgpt_mealplanner.core.model

/**
 * Describes what an inventory item is, not where it is stored.
 * StorageLocation is responsible for physical placement.
 */
enum class FoodCategory {
    PRODUCE,
    MEAT_SEAFOOD,
    DAIRY_EGGS,
    BAKERY,
    DRY_GOODS,
    TINS_CANS,
    FROZEN_FOOD,
    SAUCES_CONDIMENTS,
    SNACKS,
    DRINKS,
    READY_MEALS,
    HOUSEHOLD,
    OTHER;

    companion object {
        // Source compatibility for older callers and imported sync/backup data.
        // These are aliases, not selectable enum entries.
        @Deprecated("Use a product-type category; storage is represented by StorageLocation.")
        val CUPBOARD: FoodCategory get() = OTHER
        @Deprecated("Use a product-type category; storage is represented by StorageLocation.")
        val FRIDGE: FoodCategory get() = OTHER
        @Deprecated("Use a product-type category; storage is represented by StorageLocation.")
        val FREEZER: FoodCategory get() = OTHER
        @Deprecated("Use a product-type category; storage is represented by StorageLocation.")
        val FRESH_PANTRY: FoodCategory get() = PRODUCE
        @Deprecated("Use BAKERY.")
        val BREAD_BAKERY: FoodCategory get() = BAKERY
        @Deprecated("Use SAUCES_CONDIMENTS.")
        val SAUCES_SEASONINGS: FoodCategory get() = SAUCES_CONDIMENTS
        @Deprecated("Use READY_MEALS.")
        val MEAL_KITS: FoodCategory get() = READY_MEALS
    }
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
