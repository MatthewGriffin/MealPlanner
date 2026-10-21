package com.example.chatgpt_mealplanner.core.sync.follower.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerMealRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowerMealRequestDao {
    @Query("SELECT * FROM follower_meal_requests ORDER BY createdAt DESC")
    fun observeAllRequests(): Flow<List<FollowerMealRequestEntity>>

    @Query("SELECT * FROM follower_meal_requests WHERE id = :id")
    suspend fun getById(id: String): FollowerMealRequestEntity?

    @Query("DELETE FROM follower_meal_requests WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(request: FollowerMealRequestEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: FollowerMealRequestEntity)
}
