package com.example.chatgpt_mealplanner.core.sync.follower.database.dao

import androidx.room.*
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerMealEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowerMealDao {
    @Query("SELECT * FROM follower_meals")
    fun observeAll(): Flow<List<FollowerMealEntity>>
    @Query("SELECT * FROM follower_meals")
    suspend fun getAll(): List<FollowerMealEntity>
    @Query("DELETE FROM follower_meals")
    suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(meals: List<FollowerMealEntity>)
}
