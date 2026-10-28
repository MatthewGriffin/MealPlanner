package com.example.chatgpt_mealplanner.core.worker

import android.content.Context
import androidx.work.WorkManager

/**
 * Retailer catalogue imports are disabled while shopping uses only user-recorded local
 * purchase-price observations. Cancel any work scheduled by older app versions as well.
 */
object IcelandCatalogueRefreshScheduler {
    fun schedule(context: Context) {
        cancelLegacyCatalogueWork(context)
    }

    fun scheduleWeekly(context: Context) {
        cancelLegacyCatalogueWork(context)
    }

    private fun cancelLegacyCatalogueWork(context: Context) {
        runCatching {
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelAllWorkByTag("retail-catalogue")
            workManager.cancelUniqueWork(IcelandCatalogueRefreshWorker.LAUNCH_WORK_NAME)
            workManager.cancelUniqueWork(IcelandCatalogueRefreshWorker.PERIODIC_WORK_NAME)
        }
    }
}
