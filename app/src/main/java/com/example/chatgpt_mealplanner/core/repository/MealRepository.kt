package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import kotlinx.coroutines.flow.Flow

interface MealRepository {
    fun observeMealsForDate(date: String): Flow<List<MealWithIngredients>>
    fun observeMealsForWeek(startDate: String, endDate: String): Flow<List<MealWithIngredients>>
    fun observeAllMealTitles(): Flow<Map<String, String>>
    suspend fun getMealWithIngredients(mealId: String): MealWithIngredients?
    suspend fun insertMealWithIngredients(meal: Meal, ingredients: List<MealIngredient>)
    suspend fun updateMeal(meal: Meal)
    suspend fun setMealRating(mealId: String, rating: com.example.chatgpt_mealplanner.core.model.MealRating?)
    suspend fun updateMealIngredients(mealId: String, ingredients: List<MealIngredient>)
    suspend fun deleteMeal(mealId: String)
    suspend fun getRecentCookedMeals(daysLookback: Int): List<MealWithIngredients>
    suspend fun getUpcomingPlannedMeals(startDate: String, endDate: String): List<MealWithIngredients>
}
