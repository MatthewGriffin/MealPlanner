package com.example.chatgpt_mealplanner.core.sync.follower.engine

import com.example.chatgpt_mealplanner.core.sync.client.AuthTokenResult
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.client.FollowerSyncUploader
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

enum class CoordinatorPassOutcome {
    SUCCESS,
    SKIPPED_ACTIVE_PASS,
    RECOVERABLE_FAILURE,
    REVOKED
}

@Singleton
open class FollowerSyncCoordinator @Inject constructor(
    private val followerDatabase: FollowerDatabase?,
    private val followerSyncUploader: FollowerSyncUploader?,
    private val followerFeedPullEngine: FollowerFeedPullEngine?,
    private val followerBootstrapEngine: FollowerBootstrapEngine?,
    private val pantrySyncTestClient: PantrySyncTestClient?,
    private val followerPairingStore: FollowerPairingStore?,
    private val statusRepository: FollowerSyncStatusRepository?
) {
    private val stateLock = Any()

    private var isPassRunning = false
    private var activeOwnerToken = 0L
    private var tokenCounter = 0L
    private var requestedGeneration = 0L
    private var completedGeneration = 0L

    open fun requestManualSync(): Boolean {
        synchronized(stateLock) {
            requestedGeneration++
            val shouldEnqueue = !isPassRunning
            println("[SYNC_COORD] Manual request recorded. reqGen=$requestedGeneration compGen=$completedGeneration running=$isPassRunning -> shouldEnqueue=$shouldEnqueue")
            return shouldEnqueue
        }
    }

    open fun isRunning(): Boolean {
        synchronized(stateLock) {
            return isPassRunning
        }
    }

    open fun getGenerations(): Pair<Long, Long> {
        synchronized(stateLock) {
            return Pair(requestedGeneration, completedGeneration)
        }
    }

    open suspend fun executeSyncPass(triggerSource: String): CoordinatorPassOutcome {
        var myOwnerToken = 0L

        val acquired = synchronized(stateLock) {
            if (isPassRunning) {
                println("[SYNC_COORD] Pass active ($triggerSource). Skipping duplicate worker entry.")
                false
            } else {
                isPassRunning = true
                tokenCounter++
                myOwnerToken = tokenCounter
                activeOwnerToken = myOwnerToken
                true
            }
        }

        if (!acquired) {
            return CoordinatorPassOutcome.SKIPPED_ACTIVE_PASS
        }

        try {
            statusRepository?.reportWorkerStarted(myOwnerToken)
            var keepDraining = true
            var passSuccess = false

            while (keepDraining) {
                val currentTargetGen = synchronized(stateLock) { requestedGeneration }

                passSuccess = performSingleSyncPass(triggerSource)

                synchronized(stateLock) {
                    if (passSuccess) {
                        completedGeneration = maxOf(completedGeneration, currentTargetGen)
                        if (requestedGeneration > completedGeneration) {
                            println("[SYNC_COORD] New manual request arrived during successful pass. Continuing drain loop.")
                            keepDraining = true
                        } else {
                            if (activeOwnerToken == myOwnerToken) {
                                isPassRunning = false
                                activeOwnerToken = 0L
                            }
                            keepDraining = false
                        }
                    } else {
                        println("[SYNC_COORD] Pass failed ($triggerSource). Stopping drain loop without immediate auto-reschedule.")
                        if (activeOwnerToken == myOwnerToken) {
                            isPassRunning = false
                            activeOwnerToken = 0L
                        }
                        keepDraining = false
                    }
                }
            }

            return if (followerPairingStore?.isRevoked() == true) {
                CoordinatorPassOutcome.REVOKED
            } else if (passSuccess) {
                CoordinatorPassOutcome.SUCCESS
            } else {
                CoordinatorPassOutcome.RECOVERABLE_FAILURE
            }
        } catch (e: CancellationException) {
            println("[SYNC_COORD] Pass cancelled ($triggerSource). Releasing ownership safely.")
            throw e
        } finally {
            synchronized(stateLock) {
                if (activeOwnerToken == myOwnerToken) {
                    isPassRunning = false
                    activeOwnerToken = 0L
                }
            }
            statusRepository?.reportWorkerFinished(myOwnerToken)
        }
    }

    open suspend fun performSingleSyncPass(triggerSource: String): Boolean {
        if (followerPairingStore?.isRevoked() == true) {
            statusRepository?.reportExplicitRevocation()
            return false
        }

        val config = followerPairingStore?.getPairingConfig() ?: return true
        val hostIp = config.hostIp
        val port = config.port
        val pinnedCertFingerprint = config.pinnedCertFingerprint
        val deviceId = config.deviceId
        val keyAlias = config.keyAlias

        var activeToken = config.sessionToken
        if (activeToken.isNullOrBlank()) {
            when (val authRes = pantrySyncTestClient?.obtainSessionTokenResult(hostIp, port, pinnedCertFingerprint, deviceId, keyAlias)) {
                is AuthTokenResult.Success -> {
                    activeToken = authRes.token
                    followerPairingStore.updateSessionToken(authRes.token)
                }
                is AuthTokenResult.Revoked -> {
                    statusRepository?.reportExplicitRevocation()
                    return false
                }
                is AuthTokenResult.AuthFailure -> {
                    statusRepository?.reportAuthenticationError(authRes.message)
                    return false
                }
                is AuthTokenResult.TransportFailure -> {
                    statusRepository?.reportTransientSyncError(authRes.message)
                    return false
                }
                is AuthTokenResult.ServerFailure -> {
                    statusRepository?.reportGenericSyncError(authRes.message)
                    return false
                }
                null -> return false
            }
        }

        val tokenToUse = activeToken
        val sessionRefreshLambda: suspend (Boolean) -> String = { _ ->
            when (val refreshedRes = pantrySyncTestClient?.obtainSessionTokenResult(hostIp, port, pinnedCertFingerprint, deviceId, keyAlias)) {
                is AuthTokenResult.Success -> {
                    followerPairingStore?.updateSessionToken(refreshedRes.token)
                    refreshedRes.token
                }
                else -> throw IllegalStateException("Session refresh failed")
            }
        }

        if (followerDatabase?.followerDao() == null) return false
        val verifiedMarker = followerPairingStore?.isBootstrapVerifiedV1() == true

        if (!verifiedMarker) {
            val bootstrapSummary = followerBootstrapEngine?.performBootstrapPass(
                hostIp = hostIp, port = port, pinnedCertFingerprint = pinnedCertFingerprint,
                sessionToken = tokenToUse, followerDeviceId = deviceId, sessionTokenProvider = sessionRefreshLambda
            )
            if (bootstrapSummary?.success != true) {
                statusRepository?.reportTransientSyncError("Bootstrap failed: ${bootstrapSummary?.resultCode}")
                return false
            }
        }

        val uploadSummary = followerSyncUploader?.performSyncUpload(
            hostIp = hostIp, port = port, pinnedCertFingerprint = pinnedCertFingerprint,
            sessionToken = tokenToUse, deviceId = deviceId, sessionTokenProvider = sessionRefreshLambda
        )
        if (uploadSummary != null && uploadSummary.transportErrorCount > 0) {
            statusRepository?.reportTransientSyncError("Transport error during upload")
            return false
        }

        val feedSummary = followerFeedPullEngine?.performFeedPullPass(
            hostIp = hostIp, port = port, pinnedCertFingerprint = pinnedCertFingerprint,
            sessionToken = tokenToUse, followerDeviceId = deviceId, sessionTokenProvider = sessionRefreshLambda
        )
        if (feedSummary?.success != true) {
            when (feedSummary?.resultCode) {
                "REVOKED" -> { followerPairingStore?.markRevoked(); statusRepository?.reportExplicitRevocation(); return false }
                "AUTH_FAILURE" -> { statusRepository?.reportAuthenticationError("Feed pull auth failed"); return false }
                else -> { statusRepository?.reportTransientSyncError("Feed pull error"); return false }
            }
        }

        statusRepository?.clearSyncError()
        return true
    }
}
