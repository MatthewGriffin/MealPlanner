package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingSessionEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingTickEventEntity
import com.example.chatgpt_mealplanner.core.database.entity.StoreItemOrderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreOrderDao {
    @Query("SELECT * FROM shopping_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeOpenSession(): Flow<ShoppingSessionEntity?>

    @Query("SELECT * FROM shopping_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpenSession(): ShoppingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ShoppingSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTick(tick: ShoppingTickEventEntity)

    @Query("SELECT * FROM shopping_tick_events WHERE sessionId = :sessionId ORDER BY tickedAt ASC")
    suspend fun getTicks(sessionId: String): List<ShoppingTickEventEntity>

    @Query("SELECT * FROM store_item_order WHERE storeId = :storeId")
    fun observeOrderForStore(storeId: String): Flow<List<StoreItemOrderEntity>>

    @Query("SELECT * FROM store_item_order WHERE storeId = :storeId AND itemKey = :itemKey LIMIT 1")
    suspend fun getOrder(storeId: String, itemKey: String): StoreItemOrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: StoreItemOrderEntity)

    /** Trips that ended long ago are not needed once they have been learned from. */
    @Query("DELETE FROM shopping_tick_events WHERE sessionId IN (SELECT id FROM shopping_sessions WHERE endedAt IS NOT NULL AND endedAt < :before)")
    suspend fun deleteOldTicks(before: Long)

    @Query("DELETE FROM shopping_sessions WHERE endedAt IS NOT NULL AND endedAt < :before")
    suspend fun deleteOldSessions(before: Long)
}
