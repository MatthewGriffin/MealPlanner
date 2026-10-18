package com.example.chatgpt_mealplanner.core.model

enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK
}

enum class MealStatus {
    PLANNED,
    COOKED,
    SKIPPED,
    CANCELLED
}

enum class LeftoverStatus {
    AVAILABLE,
    CONSUMED,
    DISCARDED
}

enum class MealTag {
    PASTA,
    RICE,
    NOODLES,
    POTATO,
    BREAD,
    SOUP,
    CURRY,
    STIR_FRY,
    BAKE,
    LOW_CARB,
    BATCH_COOK
}

@kotlinx.serialization.Serializable
data class Meal(
    val id: String,
    val date: String, // ISO-8601 Date (YYYY-MM-DD)
    val mealType: MealType = MealType.DINNER,
    val title: String,
    val servings: Int = 2,
    val status: MealStatus = MealStatus.PLANNED,
    val batchPortions: Int = 2,
    val immediatePortions: Int = 2,
    val leftoverPortions: Int = 0,
    val cookInTwoBatches: Boolean = false,
    val tags: Set<MealTag> = emptySet(),
    val instructions: String? = null,
    val notes: String? = null,
    val cookedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@kotlinx.serialization.Serializable
data class MealIngredient(
    val id: String,
    val mealId: String,
    val canonicalIngredientId: String? = null,
    val displayName: String,
    val plannedQuantity: Double,
    val plannedUnit: QuantityUnit,
    val actualQuantity: Double? = null,
    val actualUnit: QuantityUnit? = null,
    val optional: Boolean = false,
    val substitutionAllowed: Boolean = true,
    val substitutedCanonicalIngredientId: String? = null,
    val substitutedDisplayName: String? = null
)

@kotlinx.serialization.Serializable
data class MealWithIngredients(
    val meal: Meal,
    val ingredients: List<MealIngredient>
)

data class Leftover(
    val id: String,
    val sourceMealId: String? = null,
    val name: String,
    val initialPortions: Int,
    val portions: Int,
    val storageLocation: StorageLocation = StorageLocation.FRIDGE,
    val status: LeftoverStatus = LeftoverStatus.AVAILABLE,
    val createdDate: String, // ISO-8601 Date (YYYY-MM-DD)
    val consumeByDate: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ActualIngredientOverride(
    val mealIngredientId: String,
    val actualQuantity: Double,
    val actualUnit: QuantityUnit
)

data class IngredientSubstitution(
    val mealIngredientId: String,
    val selectedCanonicalIngredientId: String? = null,
    val selectedInventoryItemId: String? = null,
    val actualQuantity: Double,
    val actualUnit: QuantityUnit
) {
    fun isValid(): Boolean {
        return (selectedCanonicalIngredientId != null && selectedInventoryItemId == null) ||
                (selectedCanonicalIngredientId == null && selectedInventoryItemId != null)
    }
}
