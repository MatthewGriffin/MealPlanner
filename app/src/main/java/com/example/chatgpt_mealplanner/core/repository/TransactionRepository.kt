package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.model.InventoryTransaction
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    fun getTransactionsForItem(itemId: String): Flow<List<InventoryTransaction>>
    fun getAllTransactions(): Flow<List<InventoryTransaction>>
    suspend fun logTransaction(transaction: InventoryTransaction)
}
