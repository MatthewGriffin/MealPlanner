package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.KitInstructionEntity

@Dao
interface KitInstructionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KitInstructionEntity)

    @Query("SELECT * FROM kit_instructions WHERE normalizedName = :normalizedName")
    suspend fun getByName(normalizedName: String): KitInstructionEntity?

    @Query("SELECT * FROM kit_instructions ORDER BY displayName")
    suspend fun getAll(): List<KitInstructionEntity>

    @Query("DELETE FROM kit_instructions WHERE normalizedName = :normalizedName")
    suspend fun delete(normalizedName: String)
}
