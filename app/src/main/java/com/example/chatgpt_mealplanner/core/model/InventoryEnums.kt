package com.example.chatgpt_mealplanner.core.model

/**
 * Describes what an inventory item is, not where it is stored.
 * StorageLocation is responsible for physical placement.
 */
enum class FoodCategory(val displayName: String) {
    PRODUCE("Fruit & Vegetables"),
    MEAT_SEAFOOD("Meat & Seafood"),
    DAIRY_EGGS("Dairy & Eggs"),
    BAKERY("Bakery"),
    DRY_GOODS("Dry Goods"),
    TINS_CANS("Tins & Cans"),
    FROZEN_FOOD("Frozen Food"),
    SAUCES_CONDIMENTS("Sauces & Seasonings"),
    SNACKS("Snacks"),
    DRINKS("Drinks"),
    READY_MEALS("Ready Meals"),
    HOUSEHOLD("Household"),
    OTHER("Other");

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
        val FRESH_PANTRY: FoodCategory get() = OTHER
        @Deprecated("Use BAKERY.")
        val BREAD_BAKERY: FoodCategory get() = BAKERY
        @Deprecated("Use SAUCES_CONDIMENTS.")
        val SAUCES_SEASONINGS: FoodCategory get() = SAUCES_CONDIMENTS
        @Deprecated("Use READY_MEALS.")
        val MEAL_KITS: FoodCategory get() = READY_MEALS
    }
}

enum class StorageLocation(val displayName: String) {
    CUPBOARD("Cupboard"),
    FRIDGE("Fridge"),
    FREEZER("Freezer"),
    PANTRY("Pantry"),
    OTHER("Other");

    companion object {
        // Legacy source aliases; these are no longer selectable locations.
        @Deprecated("Use PANTRY.")
        val FRESH_PANTRY: StorageLocation get() = PANTRY
        @Deprecated("Use CUPBOARD.")
        val BREAD_BAKERY: StorageLocation get() = CUPBOARD
        @Deprecated("Use CUPBOARD.")
        val SAUCES_SEASONINGS: StorageLocation get() = CUPBOARD
        @Deprecated("Use CUPBOARD.")
        val SNACKS: StorageLocation get() = CUPBOARD
        @Deprecated("Use CUPBOARD.")
        val MEAL_KITS: StorageLocation get() = CUPBOARD
    }
}

fun parseFoodCategory(rawCategory: String?): FoodCategory {
    if (rawCategory.isNullOrBlank()) return FoodCategory.OTHER
    return when (rawCategory.trim().uppercase()) {
        "BREAD_BAKERY", "BAKERY" -> FoodCategory.BAKERY
        "SAUCES_SEASONINGS", "SAUCES_CONDIMENTS" -> FoodCategory.SAUCES_CONDIMENTS
        "MEAL_KITS", "READY_MEALS" -> FoodCategory.READY_MEALS
        "PRODUCE" -> FoodCategory.PRODUCE
        "FRESH_PANTRY" -> FoodCategory.OTHER
        "SNACKS" -> FoodCategory.SNACKS
        "CUPBOARD", "FRIDGE", "FREEZER" -> FoodCategory.OTHER
        else -> runCatching { FoodCategory.valueOf(rawCategory.trim().uppercase()) }
            .getOrDefault(FoodCategory.OTHER)
    }
}

fun parseStorageLocation(rawLocation: String?): StorageLocation {
    if (rawLocation.isNullOrBlank()) return StorageLocation.OTHER
    return when (rawLocation.trim().uppercase()) {
        "FRESH_PANTRY", "PANTRY" -> StorageLocation.PANTRY
        "BREAD_BAKERY", "SAUCES_SEASONINGS", "SNACKS", "MEAL_KITS" -> StorageLocation.CUPBOARD
        else -> runCatching { StorageLocation.valueOf(rawLocation.trim().uppercase()) }
            .getOrDefault(StorageLocation.OTHER)
    }
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
