package com.example.chatgpt_mealplanner.core.sync.follower.repository

import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

enum class FollowerSyncState {
    UNPAIRED,
    BOOTSTRAP_REQUIRED,
    ACCESS_REVOKED,
    AUTHENTICATION_REQUIRED,
    SYNC_ERROR,
    SYNCING,
    PENDING_CHANGES,
    NO_PENDING_UPLOADS
}

enum class FollowerSyncErrorCategory {
    AUTHENTICATION_REQUIRED,
    TRANSIENT_NETWORK_ERROR,
    GENERIC_SYNC_ERROR
}

data class FollowerSyncStatusModel(
    val state: FollowerSyncState,
    val pendingUploadCount: Int = 0,
    val conflictCount: Int = 0,
    val pendingApprovalCount: Int = 0,
    val rejectedCount: Int = 0,
    val lastSyncTime: Long? = null,
    val canonicalCursor: Long? = null,
    val lastError: String? = null
)

object FollowerErrorClassifier {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun isExplicitRevocation(responseJson: String?): Boolean {
        if (responseJson.isNullOrBlank()) return false
        return try {
            val element = jsonParser.parseToJsonElement(responseJson.trim()).jsonObject
            val errorCode = element["errorCode"]?.jsonPrimitive?.content
            errorCode == "FOLLOWER_REVOKED"
        } catch (_: Exception) {
            false
        }
    }

    fun isExplicitRevocationFromCode(errorCode: String?): Boolean {
        return errorCode == "FOLLOWER_REVOKED"
    }
}

@Singleton
open class FollowerSyncStatusRepository @Inject constructor(
    private val followerPairingStore: FollowerPairingStore
) {
    private val _lastSyncErrorCategory = MutableStateFlow<FollowerSyncErrorCategory?>(null)
    val lastSyncErrorCategory = _lastSyncErrorCategory.asStateFlow()

    private val _lastSyncErrorMessage = MutableStateFlow<String?>(null)
    val lastSyncErrorMessage = _lastSyncErrorMessage.asStateFlow()

    private val _isRevoked = MutableStateFlow(false)
    val isRevoked = _isRevoked.asStateFlow()

    private val _isWorkerRunning = MutableStateFlow(false)
    val isWorkerRunning = _isWorkerRunning.asStateFlow()
    private val statusLock = Any()
    private var activeStatusOwnerToken = 0L

    open fun reportWorkerStarted(ownerToken: Long = 0L) {
        synchronized(statusLock) {
            if (ownerToken != 0L) {
                activeStatusOwnerToken = ownerToken
            }
            _isWorkerRunning.value = true
        }
    }

    open fun reportWorkerFinished(ownerToken: Long = 0L) {
        synchronized(statusLock) {
            if (ownerToken != 0L && activeStatusOwnerToken == ownerToken) {
                _isWorkerRunning.value = false
                activeStatusOwnerToken = 0L
            } else if (ownerToken == 0L && activeStatusOwnerToken == 0L) {
                _isWorkerRunning.value = false
            } else {
                println("[SYNC_STATUS] Stale/unowned worker finished call (token=$ownerToken, active=$activeStatusOwnerToken); preserving active Syncing status.")
            }
        }
    }

    open fun reportExplicitRevocation() {
        _isRevoked.value = true
        followerPairingStore.markRevoked()
    }

    open fun reportAuthenticationError(message: String? = null) {
        _lastSyncErrorCategory.value = FollowerSyncErrorCategory.AUTHENTICATION_REQUIRED
        _lastSyncErrorMessage.value = message ?: "Authentication required"
    }

    open fun reportTransientSyncError(error: String?) {
        if (error == null) {
            clearSyncError()
            return
        }
        _lastSyncErrorCategory.value = FollowerSyncErrorCategory.TRANSIENT_NETWORK_ERROR
        _lastSyncErrorMessage.value = error
    }

    open fun reportGenericSyncError(error: String?) {
        _lastSyncErrorCategory.value = FollowerSyncErrorCategory.GENERIC_SYNC_ERROR
        _lastSyncErrorMessage.value = error
    }

    open fun clearSyncError() {
        _lastSyncErrorCategory.value = null
        _lastSyncErrorMessage.value = null
    }

    open suspend fun calculateStatus(
        followerDao: FollowerDao?,
        isWorkerRunning: Boolean = false
    ): FollowerSyncStatusModel {
        if (!followerPairingStore.isPaired()) {
            return FollowerSyncStatusModel(
                state = FollowerSyncState.UNPAIRED
            )
        }

        if (followerPairingStore.isRevoked() || _isRevoked.value) {
            return FollowerSyncStatusModel(
                state = FollowerSyncState.ACCESS_REVOKED,
                lastError = _lastSyncErrorMessage.value ?: "Access revoked by Main"
            )
        }

        if (followerDao == null) {
            return FollowerSyncStatusModel(
                state = FollowerSyncState.UNPAIRED
            )
        }

        val meta = followerDao.getSyncMetadata()
        if (meta?.bootstrapCompleted != true) {
            return FollowerSyncStatusModel(
                state = FollowerSyncState.BOOTSTRAP_REQUIRED,
                lastSyncTime = meta?.lastSuccessfulPullAt
            )
        }

        val pendingOps = followerDao.getPendingOperationsInOrder()
        val allOps = followerDao.getAllOutboxOps()

        val pendingUploadCount = pendingOps.size
        val conflictCount = allOps.count { it.status == "CONFLICT" }
        val pendingApprovalCount = allOps.count { it.status == "PENDING_APPROVAL" }
        val rejectedCount = allOps.count { it.status == "REJECTED" }

        val errCategory = _lastSyncErrorCategory.value
        val lastErr = _lastSyncErrorMessage.value

        // Deterministic Precedence:
        // 1. UNPAIRED / ACCESS_REVOKED (Checked above)
        // 2. BOOTSTRAP_REQUIRED (Checked above)
        // 3. AUTHENTICATION_REQUIRED (Structured Category)
        // 4. SYNC_ERROR (Structured Category)
        // 5. SYNCING (WorkManager RUNNING)
        // 6. PENDING_CHANGES (pendingUploadCount > 0)
        // 7. NO_PENDING_UPLOADS (Idle)

        val isCurrentlyRunning = _isWorkerRunning.value || isWorkerRunning

        val state = when {
            errCategory == FollowerSyncErrorCategory.AUTHENTICATION_REQUIRED -> {
                FollowerSyncState.AUTHENTICATION_REQUIRED
            }
            errCategory == FollowerSyncErrorCategory.TRANSIENT_NETWORK_ERROR || errCategory == FollowerSyncErrorCategory.GENERIC_SYNC_ERROR -> {
                FollowerSyncState.SYNC_ERROR
            }
            isCurrentlyRunning -> {
                FollowerSyncState.SYNCING
            }
            pendingUploadCount > 0 -> {
                FollowerSyncState.PENDING_CHANGES
            }
            else -> {
                FollowerSyncState.NO_PENDING_UPLOADS
            }
        }

        return FollowerSyncStatusModel(
            state = state,
            pendingUploadCount = pendingUploadCount,
            conflictCount = conflictCount,
            pendingApprovalCount = pendingApprovalCount,
            rejectedCount = rejectedCount,
            lastSyncTime = meta.lastSuccessfulPullAt,
            canonicalCursor = meta.lastReconciledCanonicalSequence,
            lastError = lastErr
        )
    }
}
