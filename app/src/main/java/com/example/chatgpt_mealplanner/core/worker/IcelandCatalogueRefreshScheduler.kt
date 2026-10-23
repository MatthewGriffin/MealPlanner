package com.example.chatgpt_mealplanner.core.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object IcelandCatalogueRefreshScheduler {
    /** Check the last successful refresh on every app launch without blocking the UI. */
    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val seedRequest = OneTimeWorkRequestBuilder<IcelandStarterCatalogueSeedWorker>()
            .addTag("retail-catalogue")
            .build()
        val refreshRequest = OneTimeWorkRequestBuilder<IcelandCatalogueRefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("retail-catalogue")
            .build()
        runCatching {
            WorkManager.getInstance(appContext)
                .beginUniqueWork(
                    IcelandCatalogueRefreshWorker.LAUNCH_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    seedRequest
                )
                .then(refreshRequest)
                .enqueue()
        }
    }

    /** Start/retain weekly refresh only after a successful or still-fresh cache check. */
    fun scheduleWeekly(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<IcelandCatalogueRefreshWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .addTag("retail-catalogue")
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                IcelandCatalogueRefreshWorker.PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
