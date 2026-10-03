package com.example.chatgpt_mealplanner.core.startup

import com.example.chatgpt_mealplanner.core.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

enum class AppHealthStatus {
    HEALTHY,
    DEGRADED_OFFLINE,
    DATABASE_CORRUPT
}

@Singleton
class AppStartupManager @Inject constructor(
    private val database: AppDatabase
) {

    suspend fun checkAppHealth(): AppHealthStatus {
        return try {
            // Check Room database accessibility
            database.inventoryDao().getAllItemsSync()
            AppHealthStatus.HEALTHY
        } catch (e: Exception) {
            AppHealthStatus.DATABASE_CORRUPT
        }
    }
}
