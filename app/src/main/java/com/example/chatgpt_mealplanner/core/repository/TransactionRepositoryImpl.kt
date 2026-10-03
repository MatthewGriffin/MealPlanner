package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.InventoryTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val transactionDao: TransactionDao
) : TransactionRepository {

    override fun getTransactionsForItem(itemId: String): Flow<List<InventoryTransaction>> =
        transactionDao.getTransactionsForItem(itemId).map { list -> list.map { it.toDomainModel() } }

    override fun getAllTransactions(): Flow<List<InventoryTransaction>> =
        transactionDao.getAllTransactions().map { list -> list.map { it.toDomainModel() } }

    override suspend fun logTransaction(transaction: InventoryTransaction) {
        transactionDao.insertTransaction(transaction.toEntity())
    }
}
