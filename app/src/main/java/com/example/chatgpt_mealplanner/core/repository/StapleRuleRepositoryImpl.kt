package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.StapleRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StapleRuleRepositoryImpl @Inject constructor(
    private val stapleRuleDao: StapleRuleDao
) : StapleRuleRepository {

    override fun observeAllStapleRules(): Flow<List<StapleRule>> =
        stapleRuleDao.observeAllStapleRules().map { list -> list.map { it.toDomainModel() } }

    override suspend fun getEnabledStapleRules(): List<StapleRule> =
        stapleRuleDao.getEnabledStapleRules().map { it.toDomainModel() }

    override suspend fun getStapleRuleByCanonicalIngredientId(canonicalId: String): StapleRule? =
        stapleRuleDao.getStapleRuleByCanonicalIngredientId(canonicalId)?.toDomainModel()

    override suspend fun saveStapleRule(rule: StapleRule) {
        stapleRuleDao.insertOrUpdateStapleRule(rule.toEntity())
    }

    override suspend fun deleteStapleRule(id: String) {
        stapleRuleDao.deleteStapleRule(id)
    }
}
