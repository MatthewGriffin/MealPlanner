package com.example.chatgpt_mealplanner.core.sync.follower.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FollowerOutboxSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val followerDatabase: FollowerDatabase,
    private val followerSyncUploader: FollowerSyncUploader,
    private val followerFeedPullEngine: FollowerFeedPullEngine,
    private val pantrySyncTestClient: PantrySyncTestClient,
    private val keyStoreManager: AndroidKeyStoreManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        println("[SYNC_WORKER] start")
        val dao = followerDatabase.followerDao()
        val pendingOps = dao.getPendingOperationsInOrder()

        val hostIp = "192.168.0.126"
        val port = 8443
        val deviceId = pendingOps.firstOrNull()?.deviceId ?: "0d1f73b6-94e8-466d-9b57-61c1692e21bb"

        val summary = followerSyncUploader.performSyncUpload(
            hostIp = hostIp,
            port = port,
            pinnedCertFingerprint = null,
            sessionToken = "dummy_session_token_for_worker",
            deviceId = deviceId
        )

        if (summary.transportErrorCount == 0) {
            followerFeedPullEngine.performFeedPullPass(
                hostIp = hostIp,
                port = port,
                pinnedCertFingerprint = null,
                sessionToken = "dummy_session_token_for_worker",
                followerDeviceId = deviceId
            )
        }

        println("[SYNC_WORKER] finish summary=$summary")

        return if (summary.transportErrorCount > 0) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
