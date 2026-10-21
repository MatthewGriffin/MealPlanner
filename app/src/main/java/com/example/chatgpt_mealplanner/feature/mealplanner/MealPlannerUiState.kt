package com.example.chatgpt_mealplanner.feature.mealplanner

import com.example.chatgpt_mealplanner.core.model.ActualIngredientOverride
import com.example.chatgpt_mealplanner.core.model.IngredientSubstitution
import com.example.chatgpt_mealplanner.core.model.Leftover
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity

enum class MealPlannerTab(val displayName: String) {
    PLANNER("Meal Plan"),
    LEFTOVERS("Leftovers"),
    HISTORY("History")
}

data class MealPlannerUiState(
    val mealsForSelectedDate: List<MealWithIngredients> = emptyList(),
    val leftovers: List<Leftover> = emptyList(),
    val recentCookedMeals: List<MealWithIngredients> = emptyList(),
    val allChangeRequests: List<com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity> = emptyList(),
    val isFollower: Boolean = false,
    val selectedDate: String = "", // Populated dynamically with today's ISO date YYYY-MM-DD
    val activeTab: MealPlannerTab = MealPlannerTab.PLANNER,
    val isLoading: Boolean = false,
    val isMealPlanLoading: Boolean = true,
    val userMessage: String? = null,
    val mealToCook: MealWithIngredients? = null,
    val mealToAddOrEdit: MealWithIngredients? = null,
    val showAddDialog: Boolean = false,
    val mealTitleMap: Map<String, String> = emptyMap()
)

internal fun requestsForExistingMealIds(
    requests: List<MealChangeRequestEntity>,
    existingMealIds: Set<String>
): List<MealChangeRequestEntity> = requests.filter { it.mealId in existingMealIds }

sealed interface MealPlannerUiEvent {
    data class SelectDate(val date: String) : MealPlannerUiEvent
    data class SelectTab(val tab: MealPlannerTab) : MealPlannerUiEvent
    object RequestAddMeal : MealPlannerUiEvent
    data class RequestEditMeal(val mealWithIngredients: MealWithIngredients) : MealPlannerUiEvent
    data class SaveMeal(val meal: Meal, val ingredients: List<MealIngredient>) : MealPlannerUiEvent
    data class DeleteMeal(val mealId: String) : MealPlannerUiEvent
    data class RequestCookMeal(val mealWithIngredients: MealWithIngredients) : MealPlannerUiEvent
    data class ConfirmCookMeal(
        val mealId: String,
        val actualIngredientOverrides: List<ActualIngredientOverride> = emptyList(),
        val substitutions: List<IngredientSubstitution> = emptyList(),
        val leftoverStorageLocation: StorageLocation? = null
    ) : MealPlannerUiEvent
    data class SkipMeal(val mealId: String) : MealPlannerUiEvent
    data class CancelMeal(val mealId: String) : MealPlannerUiEvent
    object DismissCookDialog : MealPlannerUiEvent
    object DismissAddEditDialog : MealPlannerUiEvent
    data class ConsumeLeftover(val leftoverId: String, val portions: Int = 1) : MealPlannerUiEvent
    data class DiscardLeftover(val leftoverId: String) : MealPlannerUiEvent
    object ClearUserMessage : MealPlannerUiEvent
}
