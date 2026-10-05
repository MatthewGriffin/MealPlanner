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

data class PairDeviceUiState(
    val invitationPayload: QrInvitationPayloadDto? = null,
    val qrBitmap: Bitmap? = null,
    val remainingSeconds: Int = 300,
    val isExpired: Boolean = false
)

@HiltViewModel
class PairDeviceViewModel @Inject constructor(
    private val identityManager: HouseholdIdentityManager,
    private val tlsKeyManager: PantryTlsKeyManager,
    private val invitationManager: PantryEnrollmentInvitationManager
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(PairDeviceUiState())
    val uiState: StateFlow<PairDeviceUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null

    init {
        generateInvitation()
    }

    fun generateInvitation() {
        countdownJob?.cancel()
        viewModelScope.launch {
            val household = identityManager.getOrCreateHouseholdIdentity()
            val p12Store = tlsKeyManager.getOrCreateMainTlsKeyStore(household.householdId)
            val certFingerprint = tlsKeyManager.getCertificateFingerprint(p12Store)
            val hostIp = NetworkIpDiscovery.getLocalIpAddress()

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
                    isExpired = false
                )
            }

            startCountdown()
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
