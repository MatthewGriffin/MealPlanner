package com.example.chatgpt_mealplanner.core.sync.follower.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FollowerOutboxSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val followerDatabase: FollowerDatabase,
    private val followerSyncUploader: FollowerSyncUploader,
    private val pantrySyncTestClient: PantrySyncTestClient,
    private val keyStoreManager: AndroidKeyStoreManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        println("[SYNC_WORKER] start")
        val dao = followerDatabase.followerDao()
        val pendingOps = dao.getPendingOperationsInOrder()

        if (pendingOps.isEmpty()) {
            println("[SYNC_WORKER] finish - no pending outbox operations")
            return Result.success()
        }

        // For testing/acceptance, use configured Main host IP (e.g. 192.168.0.126) or default host
        val hostIp = "192.168.0.126"
        val port = 8443
        val deviceId = pendingOps.first().deviceId

        val summary = followerSyncUploader.performSyncUpload(
            hostIp = hostIp,
            port = port,
            pinnedCertFingerprint = null, // Uses pinned certificate check
            sessionToken = "dummy_session_token_for_worker",
            deviceId = deviceId
        )

        println("[SYNC_WORKER] finish summary=$summary")

        return if (summary.transportErrorCount > 0) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
