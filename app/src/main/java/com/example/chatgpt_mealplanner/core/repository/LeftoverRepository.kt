package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.model.Leftover
import kotlinx.coroutines.flow.Flow

interface LeftoverRepository {
    fun observeAvailableLeftovers(): Flow<List<Leftover>>
    suspend fun getLeftoverById(id: String): Leftover?
    suspend fun insertLeftover(leftover: Leftover)
    suspend fun updateLeftover(leftover: Leftover)
    suspend fun deleteLeftover(id: String)
}
