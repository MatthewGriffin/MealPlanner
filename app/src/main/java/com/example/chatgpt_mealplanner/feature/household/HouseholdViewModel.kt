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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HouseholdUiState(
    val isLoading: Boolean = true,
    val householdIdentity: HouseholdIdentityEntity? = null,
    val pairedDevices: List<PairedDeviceEntity> = emptyList(),
    val openConflicts: List<SyncConflictEntity> = emptyList(),
    val openApprovals: List<SyncPendingApprovalEntity> = emptyList(),
    val deviceToRevoke: PairedDeviceEntity? = null,
    val isFollowerPaired: Boolean = false,
    val hostIp: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val identityManager: HouseholdIdentityManager,
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val followerPairingStore: FollowerPairingStore,
    private val syncConflictApprovalRepository: SyncConflictApprovalRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HouseholdUiState())
    val uiState: StateFlow<HouseholdUiState> = _uiState.asStateFlow()

    init {
        loadHouseholdInfo()
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

            _uiState.update {
                it.copy(
                    isLoading = false,
                    householdIdentity = identity,
                    pairedDevices = devices,
                    openConflicts = conflicts,
                    openApprovals = approvals,
                    isFollowerPaired = isPaired,
                    hostIp = config?.hostIp
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
