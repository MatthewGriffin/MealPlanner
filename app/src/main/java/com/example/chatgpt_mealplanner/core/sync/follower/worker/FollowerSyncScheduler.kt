package com.example.chatgpt_mealplanner.core.sync.follower.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class FollowerSyncScheduler @Inject constructor() {

    open fun scheduleSyncWork(context: Context?, triggerReason: String) {
        println("[SYNC_SCHEDULE] trigger=$triggerReason")
        try {
            android.util.Log.i("AI_MEALPLANNER", "[SYNC_SCHEDULE] trigger=$triggerReason")
        } catch (_: Throwable) {}

        if (context == null) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<FollowerOutboxSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    companion object {
        const val WORK_NAME = "pantry_follower_outbox_sync"
    }
}
