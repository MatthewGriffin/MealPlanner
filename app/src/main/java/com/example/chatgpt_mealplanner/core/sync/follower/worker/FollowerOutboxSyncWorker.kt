package com.example.chatgpt_mealplanner.core.sync.follower.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerBootstrapEngine
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerFeedPullEngine
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository
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
    private val followerPairingStore: FollowerPairingStore,
    private val statusRepository: FollowerSyncStatusRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        println("[SYNC_WORKER] start")
        if (followerPairingStore.isRevoked()) {
            statusRepository.reportExplicitRevocation()
            return Result.failure()
        }

        val config = followerPairingStore.getPairingConfig() ?: return Result.success()

        val hostIp = config.hostIp
        val port = config.port
        val pinnedCertFingerprint = config.pinnedCertFingerprint
        val deviceId = config.deviceId
        val keyAlias = config.keyAlias

        var activeToken = config.sessionToken
        if (activeToken.isNullOrBlank()) {
            when (val authRes = pantrySyncTestClient.obtainSessionTokenResult(hostIp, port, pinnedCertFingerprint, deviceId, keyAlias)) {
                is AuthTokenResult.Success -> {
                    activeToken = authRes.token
                    followerPairingStore.updateSessionToken(authRes.token)
                }
                is AuthTokenResult.Revoked -> {
                    statusRepository.reportExplicitRevocation()
                    return Result.failure()
                }
                is AuthTokenResult.AuthFailure -> {
                    if (followerPairingStore.isRevoked()) {
                        statusRepository.reportExplicitRevocation()
                        return Result.failure()
                    }
                    statusRepository.reportAuthenticationError(authRes.message)
                    return Result.retry()
                }
                is AuthTokenResult.TransportFailure -> {
                    if (followerPairingStore.isRevoked()) {
                        statusRepository.reportExplicitRevocation()
                        return Result.failure()
                    }
                    statusRepository.reportTransientSyncError(authRes.message)
                    return Result.retry()
                }
                is AuthTokenResult.ServerFailure -> {
                    if (followerPairingStore.isRevoked()) {
                        statusRepository.reportExplicitRevocation()
                        return Result.failure()
                    }
                    statusRepository.reportGenericSyncError(authRes.message)
                    return Result.retry()
                }
            }
        }

        val tokenToUse = activeToken

        val sessionRefreshLambda: suspend (Boolean) -> String = { _ ->
            when (val refreshedRes = pantrySyncTestClient.obtainSessionTokenResult(hostIp, port, pinnedCertFingerprint, deviceId, keyAlias)) {
                is AuthTokenResult.Success -> {
                    followerPairingStore.updateSessionToken(refreshedRes.token)
                    refreshedRes.token
                }
                is AuthTokenResult.Revoked -> {
                    statusRepository.reportExplicitRevocation()
                    throw IllegalStateException("Session refresh failed: Device revoked")
                }
                is AuthTokenResult.AuthFailure -> {
                    if (followerPairingStore.isRevoked()) {
                        statusRepository.reportExplicitRevocation()
                    } else {
                        statusRepository.reportAuthenticationError(refreshedRes.message)
                    }
                    throw IllegalStateException("Session refresh failed: Auth failure")
                }
                is AuthTokenResult.TransportFailure -> {
                    if (followerPairingStore.isRevoked()) {
                        statusRepository.reportExplicitRevocation()
                    } else {
                        statusRepository.reportTransientSyncError(refreshedRes.message)
                    }
                    throw IllegalStateException("Session refresh failed: Transport failure")
                }
                is AuthTokenResult.ServerFailure -> {
                    if (followerPairingStore.isRevoked()) {
                        statusRepository.reportExplicitRevocation()
                    } else {
                        statusRepository.reportGenericSyncError(refreshedRes.message)
                    }
                    throw IllegalStateException("Session refresh failed: Server failure")
                }
            }
        }

        val dao = followerDatabase.followerDao()
        val syncMeta = dao.getSyncMetadata()
        if (syncMeta?.bootstrapCompleted != true) {
            val bootstrapSummary = followerBootstrapEngine.performBootstrapPass(
                hostIp = hostIp,
                port = port,
                pinnedCertFingerprint = pinnedCertFingerprint,
                sessionToken = tokenToUse,
                followerDeviceId = deviceId,
                sessionTokenProvider = sessionRefreshLambda
            )
            if (!bootstrapSummary.success) {
                if (followerPairingStore.isRevoked()) {
                    statusRepository.reportExplicitRevocation()
                    return Result.failure()
                }
                statusRepository.reportTransientSyncError("Bootstrap failed")
                return Result.retry()
            }
        }

        val summary = followerSyncUploader.performSyncUpload(
            hostIp = hostIp,
            port = port,
            pinnedCertFingerprint = pinnedCertFingerprint,
            sessionToken = tokenToUse,
            deviceId = deviceId
        )

        if (summary.transportErrorCount == 0) {
            followerFeedPullEngine.performFeedPullPass(
                hostIp = hostIp,
                port = port,
                pinnedCertFingerprint = pinnedCertFingerprint,
                sessionToken = tokenToUse,
                followerDeviceId = deviceId
            )
            statusRepository.clearSyncError()
        } else {
            if (followerPairingStore.isRevoked()) {
                statusRepository.reportExplicitRevocation()
                return Result.failure()
            }
            statusRepository.reportTransientSyncError("Transport error during upload")
        }

        println("[SYNC_WORKER] finish summary=$summary")

        return if (summary.transportErrorCount > 0) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
