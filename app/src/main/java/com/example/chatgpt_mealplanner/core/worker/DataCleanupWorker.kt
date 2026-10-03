package com.example.chatgpt_mealplanner.core.worker

import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.database.dao.RetailDao
import com.example.chatgpt_mealplanner.retail.contract.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataCleanupWorker @Inject constructor(
    private val chatDao: ChatDao,
    private val retailDao: RetailDao,
    private val timeProvider: TimeProvider
) {

    suspend fun performCleanup() {
        val now = timeProvider.currentTimeMillis()
        val thirtyDaysAgo = now - (30 * 24 * 60 * 60 * 1000L)

        // Retention 1: Delete AI tool execution logs older than 30 days
        chatDao.deleteLogsOlderThan(thirtyDaysAgo)
    }
}
