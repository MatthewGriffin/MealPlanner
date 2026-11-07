package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.chatgpt_mealplanner.core.database.entity.MealEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealIngredientEntity
import kotlinx.coroutines.flow.Flow

data class MealWithIngredientEntities(
    @androidx.room.Embedded val meal: MealEntity,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "mealId"
    )
    val ingredients: List<MealIngredientEntity>
)

@Dao
interface MealDao {

    @Transaction
    @Query("SELECT * FROM meals WHERE date = :date")
    fun observeMealsForDate(date: String): Flow<List<MealWithIngredientEntities>>

    @Transaction
    @Query("SELECT * FROM meals WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC")
    fun observeMealsForWeek(startDate: String, endDate: String): Flow<List<MealWithIngredientEntities>>

    @Transaction
    @Query("SELECT * FROM meals")
    fun observeAllMeals(): Flow<List<MealWithIngredientEntities>>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :mealId")
    suspend fun getMealWithIngredients(mealId: String): MealWithIngredientEntities?

    @Transaction
    @Query("SELECT * FROM meals ORDER BY createdAt ASC")
    suspend fun getAllMealsWithIngredientsSync(): List<MealWithIngredientEntities>

    @Transaction
    @Query("SELECT * FROM meals WHERE status = 'COOKED' AND cookedAt >= :sinceTimestamp ORDER BY cookedAt DESC")
    suspend fun getRecentCookedMeals(sinceTimestamp: Long): List<MealWithIngredientEntities>

    @Transaction
    @Query("SELECT * FROM meals WHERE date >= :startDate AND date <= :endDate AND status = 'PLANNED' ORDER BY date ASC")
    suspend fun getUpcomingPlannedMeals(startDate: String, endDate: String): List<MealWithIngredientEntities>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeal(meal: MealEntity)

    @Update
    suspend fun updateMeal(meal: MealEntity)

    @Query("UPDATE meals SET rating = :rating, ratedAt = :ratedAt WHERE id = :mealId")
    suspend fun setRating(mealId: String, rating: com.example.chatgpt_mealplanner.core.model.MealRating?, ratedAt: Long?)

    @Query("SELECT title, rating FROM meals WHERE rating IS NOT NULL ORDER BY ratedAt DESC LIMIT :limit")
    suspend fun getRatedMealTitles(limit: Int): List<com.example.chatgpt_mealplanner.core.database.entity.RatedMealTitle>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealIngredients(ingredients: List<MealIngredientEntity>)

    @Query("DELETE FROM meal_ingredients WHERE mealId = :mealId")
    suspend fun deleteMealIngredients(mealId: String)

    @Query("DELETE FROM meals WHERE id = :mealId")
    suspend fun deleteMeal(mealId: String)
}
