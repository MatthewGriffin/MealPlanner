package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chatgpt_mealplanner.core.database.entity.InventoryTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Query("SELECT * FROM inventory_transactions WHERE inventoryItemId = :itemId ORDER BY timestamp DESC")
    fun getTransactionsForItem(itemId: String): Flow<List<InventoryTransactionEntity>>

    @Query("SELECT * FROM inventory_transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<InventoryTransactionEntity>>

    @Query("SELECT * FROM inventory_transactions ORDER BY timestamp ASC")
    suspend fun getAllTransactionsSync(): List<InventoryTransactionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM inventory_transactions WHERE mealId = :mealId AND type = 'COOKED')")
    suspend fun hasCookedTransactionForMeal(mealId: String): Boolean

    @Query("SELECT * FROM inventory_transactions WHERE mealId = :mealId ORDER BY timestamp DESC")
    suspend fun getTransactionsForMeal(mealId: String): List<InventoryTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: InventoryTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<InventoryTransactionEntity>)
}
