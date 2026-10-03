package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealRepositoryImpl @Inject constructor(
    private val mealDao: MealDao
) : MealRepository {

    override fun observeMealsForDate(date: String): Flow<List<MealWithIngredients>> {
        return mealDao.observeMealsForDate(date).map { list ->
            list.map { item ->
                MealWithIngredients(
                    meal = item.meal.toDomainModel(),
                    ingredients = item.ingredients.map { it.toDomainModel() }
                )
            }
        }
    }

    override fun observeMealsForWeek(startDate: String, endDate: String): Flow<List<MealWithIngredients>> {
        return mealDao.observeMealsForWeek(startDate, endDate).map { list ->
            list.map { item ->
                MealWithIngredients(
                    meal = item.meal.toDomainModel(),
                    ingredients = item.ingredients.map { it.toDomainModel() }
                )
            }
        }
    }

    override suspend fun getMealWithIngredients(mealId: String): MealWithIngredients? {
        val entity = mealDao.getMealWithIngredients(mealId) ?: return null
        return MealWithIngredients(
            meal = entity.meal.toDomainModel(),
            ingredients = entity.ingredients.map { it.toDomainModel() }
        )
    }

    override suspend fun insertMealWithIngredients(meal: Meal, ingredients: List<MealIngredient>) {
        mealDao.insertMeal(meal.toEntity())
        mealDao.insertMealIngredients(ingredients.map { it.toEntity() })
    }

    override suspend fun updateMeal(meal: Meal) {
        mealDao.insertMeal(meal.toEntity())
    }

    override suspend fun updateMealIngredients(mealId: String, ingredients: List<MealIngredient>) {
        mealDao.deleteMealIngredients(mealId)
        mealDao.insertMealIngredients(ingredients.map { it.toEntity() })
    }

    override suspend fun deleteMeal(mealId: String) {
        mealDao.deleteMeal(mealId)
    }

    override suspend fun getRecentCookedMeals(daysLookback: Int): List<MealWithIngredients> {
        val sinceTimestamp = System.currentTimeMillis() - (daysLookback * 24L * 60 * 60 * 1000)
        return mealDao.getRecentCookedMeals(sinceTimestamp).map { item ->
            MealWithIngredients(
                meal = item.meal.toDomainModel(),
                ingredients = item.ingredients.map { it.toDomainModel() }
            )
        }
    }

    override suspend fun getUpcomingPlannedMeals(startDate: String, endDate: String): List<MealWithIngredients> {
        return mealDao.getUpcomingPlannedMeals(startDate, endDate).map { item ->
            MealWithIngredients(
                meal = item.meal.toDomainModel(),
                ingredients = item.ingredients.map { it.toDomainModel() }
            )
        }
    }
}
