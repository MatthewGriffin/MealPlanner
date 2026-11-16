package com.example.chatgpt_mealplanner.core.sync.follower.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerShoppingSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowerShoppingSourceDao {
    @Query("SELECT * FROM follower_shopping_sources")
    fun observeAll(): Flow<List<FollowerShoppingSourceEntity>>

    @Query("SELECT * FROM follower_shopping_sources")
    suspend fun getAll(): List<FollowerShoppingSourceEntity>

    @Query("DELETE FROM follower_shopping_sources")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<FollowerShoppingSourceEntity>)
}
