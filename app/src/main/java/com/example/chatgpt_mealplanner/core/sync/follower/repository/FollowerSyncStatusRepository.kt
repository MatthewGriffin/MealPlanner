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

data class FollowerSyncStatusModel(
    val state: FollowerSyncState,
    val pendingUploadCount: Int = 0,
    val conflictCount: Int = 0,
    val pendingApprovalCount: Int = 0,
    val rejectedCount: Int = 0,
    val lastSyncTime: Long? = null,
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
    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError = _lastSyncError.asStateFlow()

    private val _isRevoked = MutableStateFlow(false)
    val isRevoked = _isRevoked.asStateFlow()

    open fun reportExplicitRevocation() {
        _isRevoked.value = true
        followerPairingStore.markRevoked()
    }

    open fun reportTransientSyncError(error: String?) {
        _lastSyncError.value = error
        // Transient error reporting NEVER mutates durable revocation status!
    }

    open fun clearSyncError() {
        _lastSyncError.value = null
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
                lastError = _lastSyncError.value ?: "Access revoked by Main"
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

        val lastErr = _lastSyncError.value

        // Deterministic Precedence:
        // 1. UNPAIRED / ACCESS_REVOKED (Checked above)
        // 2. BOOTSTRAP_REQUIRED (Checked above)
        // 3. AUTHENTICATION_REQUIRED (Session refresh failure)
        // 4. SYNC_ERROR (Transport failure)
        // 5. SYNCING (WorkManager RUNNING)
        // 6. PENDING_CHANGES (pendingUploadCount > 0)
        // 7. NO_PENDING_UPLOADS (Idle)

        val state = when {
            lastErr != null && lastErr.contains("AUTH_EXPIRED", ignoreCase = true) -> {
                FollowerSyncState.AUTHENTICATION_REQUIRED
            }
            lastErr != null && (lastErr.contains("NETWORK", ignoreCase = true) || lastErr.contains("TRANSPORT", ignoreCase = true) || lastErr.contains("CONNECT", ignoreCase = true)) -> {
                FollowerSyncState.SYNC_ERROR
            }
            isWorkerRunning -> {
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
            lastError = lastErr
        )
    }
}
