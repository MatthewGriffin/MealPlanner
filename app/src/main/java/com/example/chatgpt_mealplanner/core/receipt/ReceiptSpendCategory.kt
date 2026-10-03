package com.example.chatgpt_mealplanner.core.receipt

enum class ReceiptSpendCategory(val displayName: String) {
    FOOD("Food"),
    DRINKS("Drinks"),
    HOUSEHOLD_CLEANING("Household & Cleaning"),
    PERSONAL_CARE("Toiletries & Personal Care"),
    PET("Pet"),
    BABY("Baby"),
    OTHER("Other"),
    UNCATEGORISED("Uncategorised");

    companion object {
        fun fromName(name: String): ReceiptSpendCategory {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
                ?: UNCATEGORISED
        }

        fun classifyDescription(normDesc: String): ReceiptSpendCategory {
            val lower = normDesc.lowercase()
            return when {
                lower.contains("pizza") || lower.contains("frozen") || lower.contains("ice cream") ||
                        lower.contains("wedges") || lower.contains("milk") || lower.contains("cheese") ||
                        lower.contains("chicken") || lower.contains("ham") || lower.contains("dips") ||
                        lower.contains("meat") || lower.contains("bread") || lower.contains("pasta") ||
                        lower.contains("rice") || lower.contains("canned") || lower.contains("tin") ||
                        lower.contains("crisps") || lower.contains("chocolate") || lower.contains("biscuit") ||
                        lower.contains("snacks") || lower.contains("snack") -> FOOD

                lower.contains("juice") || lower.contains("water") || lower.contains("cola") ||
                        lower.contains("soda") || lower.contains("drink") || lower.contains("coffee") ||
                        lower.contains("tea") || lower.contains("energy") -> DRINKS

                lower.contains("cleaner") || lower.contains("cleaning") || lower.contains("bleach") ||
                        lower.contains("detergent") || lower.contains("washing liquid") || lower.contains("dishwasher") ||
                        lower.contains("toilet roll") || lower.contains("kitchen roll") || lower.contains("finish") ||
                        lower.contains("paper") -> HOUSEHOLD_CLEANING

                lower.contains("shampoo") || lower.contains("soap") || lower.contains("deodorant") ||
                        lower.contains("toothpaste") || lower.contains("toiletries") -> PERSONAL_CARE

                lower.contains("pet") || lower.contains("cat") || lower.contains("dog") -> PET

                lower.contains("baby") || lower.contains("nappy") || lower.contains("nappies") || lower.contains("formula") -> BABY

                else -> UNCATEGORISED
            }
        }
    }
}
