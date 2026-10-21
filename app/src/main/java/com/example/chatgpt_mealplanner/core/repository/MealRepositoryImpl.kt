package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealRepositoryImpl @Inject constructor(
    private val mealDao: MealDao,
    private val followerDatabase: com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase,
    private val roleResolver: com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
) : MealRepository {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private fun decode(row: com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerMealEntity) =
        json.decodeFromString<MealWithIngredients>(row.payloadJson)
    private suspend fun followerMeals() = followerDatabase.mealDao().getAll().map(::decode)


    override fun observeMealsForDate(date: String): Flow<List<MealWithIngredients>> {
        return kotlinx.coroutines.flow.flow {
            if (roleResolver.isFollowerAsync()) {
                emitAll(followerDatabase.mealDao().observeForDate(date).map { rows -> rows.map(::decode) })
            } else emitAll(mealDao.observeMealsForDate(date).map { list ->
            list.map { item ->
                MealWithIngredients(
                    meal = item.meal.toDomainModel(),
                    ingredients = item.ingredients.map { it.toDomainModel() }
                )
            }
            })
        }
    }

    override fun observeMealsForWeek(startDate: String, endDate: String): Flow<List<MealWithIngredients>> {
        return kotlinx.coroutines.flow.flow {
            if (roleResolver.isFollowerAsync()) {
                emitAll(followerDatabase.mealDao().observeAll().map { rows -> rows.map(::decode)
                    .filter { it.meal.date in startDate..endDate }.sortedBy { it.meal.date } })
            } else emitAll(mealDao.observeMealsForWeek(startDate, endDate).map { list ->
            list.map { item ->
                MealWithIngredients(
                    meal = item.meal.toDomainModel(),
                    ingredients = item.ingredients.map { it.toDomainModel() }
                )
            }
            })
        }
    }

    override fun observeAllMealTitles(): Flow<Map<String, String>> {
        return kotlinx.coroutines.flow.flow {
            if (roleResolver.isFollowerAsync()) {
                emitAll(followerDatabase.mealDao().observeAll().map { rows ->
                    rows.mapNotNull { row ->
                        runCatching {
                            val model = decode(row)
                            model.meal.id to model.meal.title
                        }.getOrNull()
                    }.toMap()
                })
            } else {
                emitAll(mealDao.observeAllMeals().map { list ->
                    list.associate { it.meal.id to it.meal.title }
                })
            }
        }
    }

    override suspend fun getMealWithIngredients(mealId: String): MealWithIngredients? {
        if (roleResolver.isFollowerAsync()) return followerMeals().find { it.meal.id == mealId }
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
        if (roleResolver.isFollowerAsync()) return followerMeals().filter {
            it.meal.status == com.example.chatgpt_mealplanner.core.model.MealStatus.COOKED && (it.meal.cookedAt ?: 0L) >= sinceTimestamp }
        return mealDao.getRecentCookedMeals(sinceTimestamp).map { item ->
            MealWithIngredients(
                meal = item.meal.toDomainModel(),
                ingredients = item.ingredients.map { it.toDomainModel() }
            )
        }
    }

    override suspend fun getUpcomingPlannedMeals(startDate: String, endDate: String): List<MealWithIngredients> {
        if (roleResolver.isFollowerAsync()) return followerMeals().filter {
            it.meal.status == com.example.chatgpt_mealplanner.core.model.MealStatus.PLANNED && it.meal.date in startDate..endDate }
        return mealDao.getUpcomingPlannedMeals(startDate, endDate).map { item ->
            MealWithIngredients(
                meal = item.meal.toDomainModel(),
                ingredients = item.ingredients.map { it.toDomainModel() }
            )
        }
    }
}
