package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import kotlinx.coroutines.flow.Flow

interface MealChangeRequestRepository {
    fun observeRequestsForMeal(mealId: String): Flow<List<MealChangeRequestEntity>>
    fun observeAllRequests(): Flow<List<MealChangeRequestEntity>>
    suspend fun submitRequest(mealId: String, requestText: String)
    suspend fun updateRequestStatus(requestId: String, status: String, mainResponse: String?)
}
