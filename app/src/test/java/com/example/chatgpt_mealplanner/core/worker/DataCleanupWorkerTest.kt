package com.example.chatgpt_mealplanner.core.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.chatgpt_mealplanner.core.database.entity.AiToolExecutionLogEntity
import com.example.chatgpt_mealplanner.core.service.FakeChatDao
import com.example.chatgpt_mealplanner.retail.contract.TimeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DataCleanupWorkerTest {

    private val now = DataCleanupWorker.RETENTION_MS * 2
    private val chatDao = FakeChatDao()

    private fun log(id: String, startedAt: Long) = AiToolExecutionLogEntity(
        id = id,
        conversationId = "conv",
        toolName = "tool",
        toolCallId = id,
        executionClass = "READ",
        executionStatus = "SUCCEEDED",
        success = true,
        startedAt = startedAt
    )

    @Test
    fun doWork_deletesOnlyLogsPastTheRetentionWindow() = runBlocking {
        chatDao.insertOrUpdateToolExecutionLog(log("expired", now - DataCleanupWorker.RETENTION_MS - 1))
        chatDao.insertOrUpdateToolExecutionLog(log("retained", now - DataCleanupWorker.RETENTION_MS + 1))

        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = DataCleanupWorker(
                appContext,
                workerParameters,
                chatDao,
                object : TimeProvider {
                    override fun currentTimeMillis() = now
                }
            )
        }
        val worker = TestListenableWorkerBuilder<DataCleanupWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf("retained"), chatDao.toolLogs.map { it.id })
    }
}
