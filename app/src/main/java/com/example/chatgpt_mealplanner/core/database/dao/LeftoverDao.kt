package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.LeftoverEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LeftoverDao {

    @Query("SELECT * FROM leftovers WHERE status = 'AVAILABLE' ORDER BY createdDate DESC")
    fun observeAvailableLeftovers(): Flow<List<LeftoverEntity>>

    @Query("SELECT * FROM leftovers WHERE id = :id")
    suspend fun getLeftoverById(id: String): LeftoverEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLeftover(leftover: LeftoverEntity)

    @Query("DELETE FROM leftovers WHERE id = :id")
    suspend fun deleteLeftover(id: String)
}
