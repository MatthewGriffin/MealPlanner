package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.model.StapleRule
import kotlinx.coroutines.flow.Flow

interface StapleRuleRepository {
    fun observeAllStapleRules(): Flow<List<StapleRule>>
    suspend fun getEnabledStapleRules(): List<StapleRule>
    suspend fun getStapleRuleByCanonicalIngredientId(canonicalId: String): StapleRule?
    suspend fun saveStapleRule(rule: StapleRule)
    suspend fun deleteStapleRule(id: String)
}
