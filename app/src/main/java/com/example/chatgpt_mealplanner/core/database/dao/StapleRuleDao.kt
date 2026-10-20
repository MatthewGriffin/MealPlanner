package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StapleRuleDao {

    @Query("SELECT * FROM staple_rules WHERE archivedByMerge = 0 ORDER BY displayName ASC")
    fun observeAllStapleRules(): Flow<List<StapleRuleEntity>>

    @Query("SELECT * FROM staple_rules WHERE enabled = 1 AND archivedByMerge = 0")
    suspend fun getEnabledStapleRules(): List<StapleRuleEntity>

    @Query("SELECT * FROM staple_rules ORDER BY createdAt ASC")
    suspend fun getAllRulesSync(): List<StapleRuleEntity>

    @Query("SELECT * FROM staple_rules WHERE canonicalIngredientId = :canonicalId LIMIT 1")
    suspend fun getStapleRuleByCanonicalIngredientId(canonicalId: String): StapleRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateStapleRule(rule: StapleRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStapleRules(rules: List<StapleRuleEntity>)

    @Query("DELETE FROM staple_rules")
    suspend fun deleteAllStapleRules()

    @Query("DELETE FROM staple_rules WHERE id = :id")
    suspend fun deleteStapleRule(id: String)

    @Query("UPDATE staple_rules SET archivedByMerge = 1 WHERE canonicalIngredientId IN (SELECT mergedItemId FROM inventory_merge_logs WHERE unmerged = 0) OR canonicalIngredientId IN (SELECT 'item_' || mergedItemId FROM inventory_merge_logs WHERE unmerged = 0) OR canonicalIngredientId IN (SELECT id FROM inventory_items WHERE status = 'DISCARDED') OR canonicalIngredientId IN (SELECT 'item_' || id FROM inventory_items WHERE status = 'DISCARDED')")
    suspend fun deactivateOrphanStapleRules()
}
