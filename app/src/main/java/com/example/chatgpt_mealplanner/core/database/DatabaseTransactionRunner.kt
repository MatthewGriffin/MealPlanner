package com.example.chatgpt_mealplanner.core.database

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

interface DatabaseTransactionRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}

@Singleton
class RoomTransactionRunner @Inject constructor(
    private val database: AppDatabase
) : DatabaseTransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return database.withTransaction(block)
    }
}
