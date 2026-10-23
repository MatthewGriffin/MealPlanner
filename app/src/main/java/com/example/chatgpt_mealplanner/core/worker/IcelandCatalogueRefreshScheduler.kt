package com.example.chatgpt_mealplanner.core.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object IcelandCatalogueRefreshScheduler {
    /**
     * WorkManager runs the first eligible periodic execution as soon as constraints permit;
     * subsequent runs are spaced at least seven days apart. The worker also checks the last
     * successful refresh timestamp, so launching the app does not force a fresh crawl.
     */
    fun schedule(context: Context) {
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
