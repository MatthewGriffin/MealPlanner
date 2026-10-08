package com.example.chatgpt_mealplanner.core.startup

import android.content.Context
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class AppHealthStatus {
    HEALTHY,
    DEGRADED_OFFLINE,
    DATABASE_CORRUPT
}

@Singleton
class AppStartupManager @Inject constructor(
    @param:ApplicationContext private val context: Context?,
    private val database: AppDatabase,
    private val followerPairingStore: FollowerPairingStore,
    private val followerDatabase: FollowerDatabase,
    private val followerSyncScheduler: FollowerSyncScheduler
) {

    suspend fun checkAppHealth(): AppHealthStatus {
        val health = try {
            database.inventoryDao().getAllItemsSync()
            AppHealthStatus.HEALTHY
        } catch (e: Exception) {
            AppHealthStatus.DATABASE_CORRUPT
        }

        if (health == AppHealthStatus.HEALTHY && followerPairingStore.isPaired()) {
            val dao = followerDatabase.followerDao()
            val meta = dao.getSyncMetadata()
            val hasPending = dao.getPendingOperationsInOrder().isNotEmpty()
            if (meta?.bootstrapCompleted != true || hasPending) {
                followerSyncScheduler.scheduleSyncWorkNonBlocking(context, "APP_START")
            }
        }

        return health
    }
}
