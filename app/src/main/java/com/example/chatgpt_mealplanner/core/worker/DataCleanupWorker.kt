package com.example.chatgpt_mealplanner.core.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.retail.contract.TimeProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Deletes AI tool execution logs once they pass the 30-day retention window. */
@HiltWorker
class DataCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val chatDao: ChatDao,
    private val timeProvider: TimeProvider
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            chatDao.deleteLogsOlderThan(timeProvider.currentTimeMillis() - RETENTION_MS)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        internal val RETENTION_MS = TimeUnit.DAYS.toMillis(30)
        private const val WORK_NAME = "data-cleanup-daily"

        fun schedule(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<DataCleanupWorker>(1, TimeUnit.DAYS).build()
                )
            }
        }
    }
}
