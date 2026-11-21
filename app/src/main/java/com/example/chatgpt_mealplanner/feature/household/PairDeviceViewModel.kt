package com.example.chatgpt_mealplanner.feature.household

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.enrollment.NetworkIpDiscovery
import com.example.chatgpt_mealplanner.core.sync.enrollment.PantryEnrollmentInvitationManager
import com.example.chatgpt_mealplanner.core.sync.enrollment.QrCodeGenerator
import com.example.chatgpt_mealplanner.core.sync.security.PantryTlsKeyManager
import com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.example.chatgpt_mealplanner.core.sync.server.PantrySyncHostController

data class PairDeviceUiState(
    val invitationPayload: QrInvitationPayloadDto? = null,
    val qrBitmap: Bitmap? = null,
    val remainingSeconds: Int = 300,
    val isExpired: Boolean = false,
    val errorMessage: String? = null,
    /** Set once a phone has used this code to join, so the screen can say so instead of counting down. */
    val pairedDeviceName: String? = null
)

@HiltViewModel
class PairDeviceViewModel @Inject constructor(
    private val identityManager: HouseholdIdentityManager,
    private val tlsKeyManager: PantryTlsKeyManager,
    private val invitationManager: PantryEnrollmentInvitationManager,
    private val hostController: PantrySyncHostController? = null,
    @param:ApplicationContext private val appContext: Context? = null,
    private val pairedDeviceRepository: com.example.chatgpt_mealplanner.core.sync.repository.PairedDeviceRepository? = null
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(PairDeviceUiState())
    val uiState: StateFlow<PairDeviceUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null
    private var watchJob: Job? = null

    override fun onCleared() {
        super.onCleared()
        onScreenLeft()
    }

    /** Leaving the pairing screen with nothing paired: stop hosting so no foreground service or notification is left running. */
    fun onScreenLeft() {
        val ctx = appContext
        val repo = pairedDeviceRepository
        val host = hostController
        if (ctx != null && repo != null && host != null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                host.stopHostIfUnused(ctx) {
                    repo.getAllDevices().any { it.status == com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceStatus.ACTIVE }
                }
            }
        }
    }

    init {
        generateInvitation()
    }

    private var firstEntryPending = true

    /**
     * Called each time the pairing screen opens. The first time, the invitation made when this was created is used. Later
     * the host may have been stopped when the screen was left, so an old code would point at nothing: make a fresh one,
     * which also starts the host again.
     */
    fun onScreenEntered() {
        if (firstEntryPending) firstEntryPending = false else generateInvitation()
    }

    fun generateInvitation() {
        countdownJob?.cancel()
        watchJob?.cancel()
        _uiState.value = PairDeviceUiState()
        viewModelScope.launch {
            if (hostController != null && !hostController.isEligibleToHost()) {
                _uiState.update {
                    it.copy(
                        invitationPayload = null,
                        qrBitmap = null,
                        errorMessage = "Follower installation cannot generate QR invitations"
                    )
                }
                return@launch
            }

            val startedAt = System.currentTimeMillis()
            val household = identityManager.getOrCreateHouseholdIdentity()
            val p12Store = tlsKeyManager.getOrCreateMainTlsKeyStore(household.householdId)
            val certFingerprint = tlsKeyManager.getCertificateFingerprint(p12Store)
            val hostIp = NetworkIpDiscovery.getLocalIpAddress()

            val ctx = appContext
            if (ctx != null && hostController != null) {
                val isReady = hostController.awaitHttpsHealthReadiness(
                    context = ctx,
                    hostIp = hostIp,
                    port = 8443,
                    expectedTlsFingerprint = certFingerprint
                )
                if (!isReady) {
                    _uiState.update {
                        it.copy(
                            invitationPayload = null,
                            qrBitmap = null,
                            errorMessage = "Household sync host couldn't start. Please try again."
                        )
                    }
                    return@launch
                }
            }

            val invitation = invitationManager.createInvitation(
                householdId = household.householdId,
                mainDeviceId = household.mainDeviceId,
                hostIp = hostIp,
                port = 8443,
                tlsFingerprint = certFingerprint,
                expiryMs = PantryEnrollmentInvitationManager.DEFAULT_EXPIRY_MS
            )

            val payloadJson = json.encodeToString(invitation)
            val bitmap = QrCodeGenerator.generateQrBitmap(payloadJson, 512)

            _uiState.update {
                it.copy(
                    invitationPayload = invitation,
                    qrBitmap = bitmap,
                    remainingSeconds = 300,
                    isExpired = false,
                    errorMessage = null
                )
            }

            startCountdown()
            watchForNewDevice(startedAt)
        }
    }

    /** Notices the phone that joins with this code, which Main otherwise only shows after leaving and reopening the screen. */
    private fun watchForNewDevice(since: Long) {
        val repo = pairedDeviceRepository ?: return
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (true) {
                val joined = runCatching { repo.getAllDevices() }.getOrNull()
                    ?.firstOrNull { it.status == com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceStatus.ACTIVE && it.pairedAt >= since }
                if (joined != null) {
                    countdownJob?.cancel()
                    _uiState.update { it.copy(pairedDeviceName = joined.deviceName) }
                    return@launch
                }
                delay(2000L)
            }
        }
    }

    private fun startCountdown() {
        countdownJob = viewModelScope.launch {
            while (_uiState.value.remainingSeconds > 0) {
                delay(1000L)
                _uiState.update { current ->
                    val nextSec = current.remainingSeconds - 1
                    current.copy(
                        remainingSeconds = nextSec,
                        isExpired = nextSec <= 0
                    )
                }
            }
        }
    }
}
