package com.example.chatgpt_mealplanner.core.sync.follower.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine
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
    private val followerBootstrapEngine: FollowerBootstrapEngine,
    private val pantrySyncTestClient: PantrySyncTestClient,
    private val keyStoreManager: AndroidKeyStoreManager,
    private val followerPairingStore: FollowerPairingStore
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        println("[SYNC_WORKER] start")
        val config = followerPairingStore.getPairingConfig() ?: return Result.success()

        val hostIp = config.hostIp
        val port = config.port
        val pinnedCertFingerprint = config.pinnedCertFingerprint
        val deviceId = config.deviceId
        val keyAlias = config.keyAlias

        var activeToken = config.sessionToken
        if (activeToken.isNullOrBlank()) {
            activeToken = pantrySyncTestClient.obtainSessionToken(
                hostIp = hostIp,
                port = port,
                pinnedCertFingerprint = pinnedCertFingerprint,
                deviceId = deviceId,
                keyAlias = keyAlias
            )
            if (activeToken.isNullOrBlank()) {
                return Result.retry()
            }
            followerPairingStore.updateSessionToken(activeToken)
        }

        val sessionRefreshLambda: suspend (Boolean) -> String = { _ ->
            val refreshedToken = pantrySyncTestClient.obtainSessionToken(
                hostIp = hostIp,
                port = port,
                pinnedCertFingerprint = pinnedCertFingerprint,
                deviceId = deviceId,
                keyAlias = keyAlias
            ) ?: throw IllegalStateException("Session refresh failed")
            followerPairingStore.updateSessionToken(refreshedToken)
            refreshedToken
        }

        val dao = followerDatabase.followerDao()
        val syncMeta = dao.getSyncMetadata()
        if (syncMeta?.bootstrapCompleted != true) {
            val bootstrapSummary = followerBootstrapEngine.performBootstrapPass(
                hostIp = hostIp,
                port = port,
                pinnedCertFingerprint = pinnedCertFingerprint,
                sessionToken = activeToken,
                followerDeviceId = deviceId,
                sessionTokenProvider = sessionRefreshLambda
            )
            if (!bootstrapSummary.success) {
                return Result.retry()
            }
        }

        val summary = followerSyncUploader.performSyncUpload(
            hostIp = hostIp,
            port = port,
            pinnedCertFingerprint = pinnedCertFingerprint,
            sessionToken = activeToken,
            deviceId = deviceId
        )

        if (summary.transportErrorCount == 0) {
            followerFeedPullEngine.performFeedPullPass(
                hostIp = hostIp,
                port = port,
                pinnedCertFingerprint = pinnedCertFingerprint,
                sessionToken = activeToken,
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
