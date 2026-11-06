package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.CookingPreferencesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CookingPreferencesDao {
    @Query("SELECT * FROM cooking_preferences WHERE id = 1")
    suspend fun get(): CookingPreferencesEntity?

    @Query("SELECT * FROM cooking_preferences WHERE id = 1")
    fun observe(): Flow<CookingPreferencesEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(preferences: CookingPreferencesEntity)
}
