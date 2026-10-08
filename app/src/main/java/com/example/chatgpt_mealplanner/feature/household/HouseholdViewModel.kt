package com.example.chatgpt_mealplanner.feature.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.repository.PairedDeviceRepository
import com.example.chatgpt_mealplanner.core.sync.repository.ResolutionResult
import com.example.chatgpt_mealplanner.core.sync.repository.RevocationResult
import com.example.chatgpt_mealplanner.core.sync.repository.SyncConflictApprovalRepository
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.example.chatgpt_mealplanner.core.sync.enrollment.PantryEnrollmentInvitationManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import javax.inject.Inject

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HouseholdUiState(
    val isLoading: Boolean = true,
    val householdIdentity: HouseholdIdentityEntity? = null,
    val pairedDevices: List<PairedDeviceEntity> = emptyList(),
    val openConflicts: List<SyncConflictEntity> = emptyList(),
    val openApprovals: List<SyncPendingApprovalEntity> = emptyList(),
    val deviceToRevoke: PairedDeviceEntity? = null,
    val activeInvitationPayloadJson: String? = null,
    val isFollowerPaired: Boolean = false,
    val hostIp: String? = null,
    val pairedHouseholdId: String? = null,
    val pairedMainDeviceId: String? = null,
    val appRole: com.example.chatgpt_mealplanner.core.sync.AppRole = com.example.chatgpt_mealplanner.core.sync.AppRole.MAIN,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val followerSyncStatus: com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusModel? = null,
    val isSyncing: Boolean = false
) {
    val truthfulStatusText: String
        get() {
            // Priority 1: Revoked or Authentication required errors
            when (followerSyncStatus?.state) {
                com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.ACCESS_REVOKED -> return "Access revoked by Main"
                com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.AUTHENTICATION_REQUIRED -> return "Authentication required"
                else -> {}
            }

            // Priority 2: Repository Sync errors or Scheduling errors
            if (followerSyncStatus?.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.SYNC_ERROR) {
                return followerSyncStatus.lastError ?: "Sync error"
            }
            if (errorMessage != null) {
                return errorMessage
            }

            // Priority 3: Actual running worker
            if (isSyncing || followerSyncStatus?.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.SYNCING) {
                return "Syncing…"
            }

            // Priority 4: Queued / Waiting state
            if (statusMessage != null) {
                return statusMessage
            }

            // Priority 5: Verified specific repository states
            return when (followerSyncStatus?.state) {
                com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.UNPAIRED -> "Not paired"
                com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.BOOTSTRAP_REQUIRED -> "Sync required"
                com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.PENDING_CHANGES -> "${followerSyncStatus.pendingUploadCount} changes waiting to upload"
                com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.NO_PENDING_UPLOADS -> "Healthy"
                else -> "Status unavailable"
            }
        }

    val isAuthError: Boolean
        get() = followerSyncStatus?.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.AUTHENTICATION_REQUIRED ||
                followerSyncStatus?.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.ACCESS_REVOKED

    val lastSuccessfulSyncFormatted: String?
        get() {
            val ts = followerSyncStatus?.lastSyncTime ?: return null
            if (ts <= 0L) return null
            val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            return sdf.format(Date(ts))
        }
}

@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val identityManager: HouseholdIdentityManager,
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val followerPairingStore: FollowerPairingStore,
    private val syncConflictApprovalRepository: SyncConflictApprovalRepository? = null,
    private val pantryEnrollmentInvitationManager: PantryEnrollmentInvitationManager? = null,
    private val tlsKeyManager: com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager? = null,
    private val hostController: com.example.chatgpt_mealplanner.core.sync.server.PantrySyncHostController? = null,
    private val appRoleResolver: com.example.chatgpt_mealplanner.core.sync.AppRoleResolver? = null,
    private val followerSyncStatusRepository: com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusRepository? = null,
    private val followerSyncScheduler: com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler? = null,
    private val followerDatabase: com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase? = null,
    @param:ApplicationContext private val appContext: Context? = null
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(HouseholdUiState())
    val uiState: StateFlow<HouseholdUiState> = _uiState.asStateFlow()

    init {
        loadHouseholdInfo()
        observeSyncStatus()
    }

    private fun observeSyncStatus() {
        val repo = followerSyncStatusRepository ?: return
        viewModelScope.launch {
            repo.isWorkerRunning.collect {
                refreshFollowerStatus()
            }
        }
    }

    fun triggerSyncNow() {
        val scheduler = followerSyncScheduler
        val shouldEnqueue = scheduler?.recordManualSyncIntent() ?: true

        _uiState.update { current ->
            current.copy(
                statusMessage = "Sync requested",
                errorMessage = null
            )
        }

        if (!shouldEnqueue) {
            refreshFollowerStatus()
            return
        }

        viewModelScope.launch {
            val result = scheduler?.scheduleSyncWorkAsync(appContext, "MANUAL_SYNC", intentAlreadyRecorded = true)
                ?: com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.OPERATION_FAILED

            when (result) {
                com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.SUBMITTED_AND_CONFIRMED,
                com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.INTENT_COALESCED_IN_MEMORY -> {
                    _uiState.update { current ->
                        current.copy(
                            statusMessage = "Sync queued",
                            errorMessage = null
                        )
                    }
                }
                com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.OPERATION_FAILED -> {
                    _uiState.update { current ->
                        current.copy(
                            statusMessage = null,
                            errorMessage = "Failed to schedule manual sync"
                        )
                    }
                }
                com.example.chatgpt_mealplanner.core.sync.follower.worker.WorkSchedulingResult.CANCELLED -> {
                    _uiState.update { current ->
                        current.copy(
                            statusMessage = null,
                            errorMessage = "Sync request cancelled"
                        )
                    }
                }
            }
            refreshFollowerStatus()
        }
    }

    fun refreshFollowerStatus() {
        viewModelScope.launch {
            val dao = followerDatabase?.followerDao()
            val status = followerSyncStatusRepository?.calculateStatus(dao)
            _uiState.update { current ->
                val isSyncingNow = status?.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.SYNCING
                val shouldClearMessage = isSyncingNow || current.errorMessage != null
                val nextStatusMessage = if (shouldClearMessage) null else current.statusMessage
                current.copy(
                    followerSyncStatus = status,
                    isSyncing = isSyncingNow,
                    statusMessage = nextStatusMessage
                )
            }
        }
    }

    fun loadHouseholdInfo() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val identity = identityManager.getOrCreateHouseholdIdentity()
            val devices = pairedDeviceRepository.getAllDevices()
            val conflicts = syncConflictApprovalRepository?.getOpenConflicts() ?: emptyList()
            val approvals = syncConflictApprovalRepository?.getOpenPendingApprovals() ?: emptyList()
            val isPaired = followerPairingStore.isPaired()
            val config = followerPairingStore.getPairingConfig()
            val role = appRoleResolver?.getAppRole() ?: if (isPaired) com.example.chatgpt_mealplanner.core.sync.AppRole.FOLLOWER else com.example.chatgpt_mealplanner.core.sync.AppRole.MAIN
            val dao = followerDatabase?.followerDao()
            val followerStatus = followerSyncStatusRepository?.calculateStatus(dao)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    householdIdentity = identity,
                    pairedDevices = devices,
                    openConflicts = conflicts,
                    openApprovals = approvals,
                    isFollowerPaired = isPaired,
                    hostIp = config?.hostIp,
                    pairedHouseholdId = config?.householdId,
                    pairedMainDeviceId = config?.mainDeviceId,
                    appRole = role,
                    followerSyncStatus = followerStatus,
                    isSyncing = followerStatus?.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.SYNCING
                )
            }
        }
    }

    fun requestRevocationConfirmation(device: PairedDeviceEntity) {
        if (device.status == "REVOKED") return
        _uiState.update { it.copy(deviceToRevoke = device) }
    }

    fun cancelRevocation() {
        _uiState.update { it.copy(deviceToRevoke = null) }
    }

    fun createReauthorizationInvitation(targetDeviceId: String) {
        viewModelScope.launch {
            if (hostController != null && !hostController.isEligibleToHost()) {
                _uiState.update {
                    it.copy(
                        activeInvitationPayloadJson = null,
                        errorMessage = "Follower installation cannot generate re-authorization invitations"
                    )
                }
                return@launch
            }

            val identity = identityManager.getOrCreateHouseholdIdentity()
            val hostIp = com.example.chatgpt_mealplanner.core.sync.enrollment.NetworkIpDiscovery.getLocalIpAddress()

            val certFingerprint = if (tlsKeyManager != null) {
                val p12Store = tlsKeyManager.getOrCreateMainTlsKeyStore(identity.householdId)
                tlsKeyManager.getCertificateFingerprint(p12Store)
            } else {
                "SHA256:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
            }

            val ctx = appContext
            if (ctx != null && hostController != null) {
                val isReady = hostController.awaitHttpsHealthReadiness(
                    context = ctx,
                    hostIp = hostIp,
                    port = 8443,
                    expectedTlsFingerprint = certFingerprint
                )
                if (!isReady) {
                    _uiState.update { it.copy(errorMessage = "Household sync host couldn't start. Please try again.") }
                    return@launch
                }
            }

            val invPayload = pantryEnrollmentInvitationManager?.createReauthorizationInvitation(
                householdId = identity.householdId,
                mainDeviceId = identity.mainDeviceId,
                targetDeviceId = targetDeviceId,
                hostIp = hostIp,
                tlsFingerprint = certFingerprint
            )

            if (invPayload != null) {
                val jsonStr = json.encodeToString(invPayload)
                _uiState.update { it.copy(activeInvitationPayloadJson = jsonStr, errorMessage = null) }
            }
        }
    }

    fun dismissReauthorizationDialog() {
        _uiState.update { it.copy(activeInvitationPayloadJson = null) }
    }

    fun confirmRevocation() {
        val device = _uiState.value.deviceToRevoke ?: return
        _uiState.update { it.copy(deviceToRevoke = null) }

        viewModelScope.launch {
            when (val res = pairedDeviceRepository.revokeDevice(device.deviceId)) {
                is RevocationResult.Success -> {
                    _uiState.update { current ->
                        current.copy(
                            statusMessage = "Revoked access for ${res.device.deviceName}",
                            errorMessage = null
                        )
                    }
                    loadHouseholdInfo()
                }
                is RevocationResult.AlreadyRevoked -> {
                    _uiState.update { current ->
                        current.copy(
                            statusMessage = "Device ${res.device.deviceName} is already revoked",
                            errorMessage = null
                        )
                    }
                    loadHouseholdInfo()
                }
                is RevocationResult.DeviceNotFound -> {
                    _uiState.update { current ->
                        current.copy(
                            errorMessage = "Device not found for revocation: ${res.deviceId}"
                        )
                    }
                }
                is RevocationResult.UnknownStatusFailure -> {
                    _uiState.update { current ->
                        current.copy(
                            errorMessage = "Cannot revoke device with status '${res.status}'"
                        )
                    }
                }
            }
        }
    }

    fun resolveConflictKeepMain(conflictId: String) {
        viewModelScope.launch {
            val repo = syncConflictApprovalRepository ?: return@launch
            when (val res = repo.resolveConflictKeepMain(conflictId)) {
                is ResolutionResult.Success -> {
                    _uiState.update { it.copy(statusMessage = "Conflict resolved: Main version kept") }
                    loadHouseholdInfo()
                }
                is ResolutionResult.AlreadyResolved -> {
                    _uiState.update { it.copy(statusMessage = "Conflict was already resolved") }
                    loadHouseholdInfo()
                }
                else -> {
                    _uiState.update { it.copy(errorMessage = "Failed to resolve conflict") }
                }
            }
        }
    }

    fun resolveConflictApplyFollower(conflictId: String) {
        viewModelScope.launch {
            val repo = syncConflictApprovalRepository ?: return@launch
            when (val res = repo.resolveConflictApplyFollower(conflictId)) {
                is ResolutionResult.Success -> {
                    _uiState.update { it.copy(statusMessage = "Conflict resolved: Follower changes applied") }
                    loadHouseholdInfo()
                }
                is ResolutionResult.AlreadyResolved -> {
                    _uiState.update { it.copy(statusMessage = "Conflict was already resolved") }
                    loadHouseholdInfo()
                }
                else -> {
                    _uiState.update { it.copy(errorMessage = "Failed to resolve conflict") }
                }
            }
        }
    }

    fun approvePendingDiscard(approvalId: String) {
        viewModelScope.launch {
            val repo = syncConflictApprovalRepository ?: return@launch
            when (val res = repo.approvePendingDiscard(approvalId)) {
                is ResolutionResult.Success -> {
                    _uiState.update { it.copy(statusMessage = "Discard request approved") }
                    loadHouseholdInfo()
                }
                is ResolutionResult.AlreadyResolved -> {
                    _uiState.update { it.copy(statusMessage = "Discard request was already processed") }
                    loadHouseholdInfo()
                }
                else -> {
                    _uiState.update { it.copy(errorMessage = "Failed to approve discard request") }
                }
            }
        }
    }

    fun rejectPendingDiscard(approvalId: String) {
        viewModelScope.launch {
            val repo = syncConflictApprovalRepository ?: return@launch
            when (val res = repo.rejectPendingDiscard(approvalId)) {
                is ResolutionResult.Success -> {
                    _uiState.update { it.copy(statusMessage = "Discard request rejected") }
                    loadHouseholdInfo()
                }
                is ResolutionResult.AlreadyResolved -> {
                    _uiState.update { it.copy(statusMessage = "Discard request was already processed") }
                    loadHouseholdInfo()
                }
                else -> {
                    _uiState.update { it.copy(errorMessage = "Failed to reject discard request") }
                }
            }
        }
    }
}
