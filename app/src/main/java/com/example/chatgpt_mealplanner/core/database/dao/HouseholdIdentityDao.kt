package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity

@Dao
interface HouseholdIdentityDao {
    @Query("SELECT * FROM household_identity LIMIT 1")
    suspend fun getHouseholdIdentity(): HouseholdIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHouseholdIdentity(identity: HouseholdIdentityEntity)
}
