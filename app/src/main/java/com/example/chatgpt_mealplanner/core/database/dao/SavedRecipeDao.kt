package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.SavedRecipeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRecipeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedRecipeEntity)

    @Query("SELECT * FROM saved_recipes WHERE normalizedTitle = :normalizedTitle")
    suspend fun getByTitle(normalizedTitle: String): SavedRecipeEntity?

    @Query("SELECT * FROM saved_recipes ORDER BY title")
    suspend fun getAll(): List<SavedRecipeEntity>

    @Query("SELECT * FROM saved_recipes ORDER BY title")
    fun observeAll(): Flow<List<SavedRecipeEntity>>

    @Query("DELETE FROM saved_recipes WHERE normalizedTitle = :normalizedTitle")
    suspend fun delete(normalizedTitle: String)
}

/** Lower case with single spaces. */
fun normalizeRecipeTitle(title: String?): String = title.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase()
