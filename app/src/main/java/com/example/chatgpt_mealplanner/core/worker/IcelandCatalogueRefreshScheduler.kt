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
            workManager.cancelUniqueWork("iceland_catalogue_refresh_on_launch")
            workManager.cancelUniqueWork("iceland_catalogue_refresh_weekly")
        }
    }
}
