package com.example.chatgpt_mealplanner.core.ai.validation

import com.example.chatgpt_mealplanner.core.model.MealIngredient

object MealOfferValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val reason: String
    )

    fun validateStructuredMealOffer(
        title: String?,
        servings: Int,
        ingredients: List<MealIngredient>
    ): ValidationResult {
        if (title.isNullOrBlank()) {
            return ValidationResult(false, "Title is blank")
        }
        if (servings <= 0) {
            return ValidationResult(false, "Servings must be > 0")
        }
        if (ingredients.isEmpty()) {
            return ValidationResult(false, "Ingredients list is empty")
        }
        val invalidIngredient = ingredients.firstOrNull { ing ->
            ing.displayName.isBlank() || ing.plannedQuantity <= 0.0
        }
        if (invalidIngredient != null) {
            return ValidationResult(
                false,
                "Ingredient '${invalidIngredient.displayName}' has invalid name or quantity (${invalidIngredient.plannedQuantity} <= 0)"
            )
        }
        return ValidationResult(true, "VALID")
    }
}
