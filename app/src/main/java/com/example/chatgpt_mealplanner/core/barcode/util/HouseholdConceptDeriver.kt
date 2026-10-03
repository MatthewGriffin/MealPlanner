package com.example.chatgpt_mealplanner.core.barcode.util

object HouseholdConceptDeriver {

    private val allowlist = mapOf(
        "en:toothpaste" to "Toothpaste",
        "toothpaste" to "Toothpaste",
        "en:toothpastes" to "Toothpaste",
        "toothpastes" to "Toothpaste"
    )

    fun deriveConcept(categoriesTags: List<String>?, categoriesString: String?, defaultName: String?): String? {
        categoriesTags?.forEach { tag ->
            val normalizedTag = tag.trim().lowercase()
            allowlist[normalizedTag]?.let { return it }
        }

        if (!categoriesString.isNullOrBlank()) {
            val parts = categoriesString.split(",").map { it.trim().lowercase() }
            parts.forEach { part ->
                allowlist[part]?.let { return it }
            }
        }

        return defaultName
    }
}
