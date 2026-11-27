package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.model.ActualIngredientOverride
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.IngredientSubstitution
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.StorageLocation

interface MealDomainService {
    suspend fun createPlannedMeal(
        meal: Meal,
        ingredients: List<MealIngredient>
    ): DomainResult<Meal>

    suspend fun moveMeal(
        mealId: String,
        newDate: String
    ): DomainResult<Unit>

    suspend fun replaceMeal(
        oldMealId: String,
        newMeal: Meal,
        newIngredients: List<MealIngredient>,
        preserveSchedule: Boolean = false
    ): DomainResult<Meal>

    suspend fun deletePlannedMeal(mealId: String): DomainResult<Unit>

    /**
     * How much of an ingredient the household has, in the ingredient's own unit, for the cooking screen. Null when it
     * is not in stock, or the amount is unknown or in a unit that cannot be compared (packs against grams).
     */
    suspend fun availableStock(ingredient: MealIngredient): Double?

    /** The uncooked "Leftovers: X" days planned for the batch that [sourceMealId] makes. */
    suspend fun leftoverDaysFor(sourceMealId: String): List<Meal>

    /** Moves a planned meal to another slot of the same day (breakfast, lunch, dinner). */
    suspend fun changeMealSlot(mealId: String, mealType: com.example.chatgpt_mealplanner.core.model.MealType): DomainResult<Unit>

    suspend fun markMealSkipped(mealId: String): DomainResult<Unit>

    suspend fun markMealCancelled(mealId: String): DomainResult<Unit>

    suspend fun restoreMealToPlanned(mealId: String): DomainResult<Unit>

    /**
     * What to tell the user about stock cooking this meal can't count, as sentences: kits it will mark used up, and items
     * it will leave unchanged because their unit can't be compared with the recipe's
     * (grams against a count) or whose amount isn't recorded. Ask before [markMealCooked], then tell the user to update them.
     */
    suspend fun stockNotCompared(
        mealId: String,
        overrides: List<ActualIngredientOverride> = emptyList(),
        substitutions: List<IngredientSubstitution> = emptyList()
    ): List<String> = emptyList()

    suspend fun markMealCooked(
        mealId: String,
        actualIngredientOverrides: List<ActualIngredientOverride> = emptyList(),
        substitutions: List<IngredientSubstitution> = emptyList(),
        leftoverStorageLocation: StorageLocation = StorageLocation.FRIDGE
    ): DomainResult<Unit>

    suspend fun consumeLeftoverPortions(
        leftoverId: String,
        portionsToConsume: Int
    ): DomainResult<Unit>

    /** Sets the portions remaining of a leftover and moves its pantry "Leftover X" item by the same amount. */
    suspend fun setLeftoverPortions(leftoverId: String, portions: Int): DomainResult<Unit>

    suspend fun discardLeftover(
        leftoverId: String
    ): DomainResult<Unit>

    /** Takes eaten portions of a batch out of the pantry's "Leftover X" item. */
    suspend fun takePantryPortions(sourceMealId: String, portions: Int)
}
