package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MealChangeRequestDao {
    @Query("SELECT * FROM meal_change_requests WHERE mealId = :mealId ORDER BY createdAt DESC")
    fun observeRequestsForMeal(mealId: String): Flow<List<MealChangeRequestEntity>>

    @Query("SELECT * FROM meal_change_requests ORDER BY createdAt DESC")
    fun observeAllRequests(): Flow<List<MealChangeRequestEntity>>

    @Query("SELECT * FROM meal_change_requests WHERE id = :requestId")
    suspend fun getRequestById(requestId: String): MealChangeRequestEntity?

    @Query("SELECT * FROM meal_change_requests WHERE mealId = :mealId ORDER BY createdAt ASC")
    suspend fun getRequestsForMealSync(mealId: String): List<MealChangeRequestEntity>

    @Query("SELECT * FROM meal_change_requests ORDER BY createdAt ASC")
    suspend fun getAllRequestsSync(): List<MealChangeRequestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: MealChangeRequestEntity)

    @Query("UPDATE meal_change_requests SET status = :status, mainResponse = :mainResponse, updatedAt = :updatedAt WHERE id = :requestId")
    suspend fun updateRequestStatus(requestId: String, status: String, mainResponse: String?, updatedAt: Long)
}
