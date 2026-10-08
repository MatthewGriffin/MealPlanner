package com.example.chatgpt_mealplanner.core.sync.enrollment

import android.content.Context
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingConfig
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentResponseDto
import com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class FollowerEnrollmentOrchestrator @Inject constructor(
    @param:ApplicationContext private val context: Context?,
    private val identityStore: FollowerIdentityStore,
    private val keyStoreManager: AndroidKeyStoreManager,
    private val followerPairingStore: FollowerPairingStore,
    private val pantrySyncClient: PantrySyncTestClient,
    private val followerSyncScheduler: FollowerSyncScheduler,
    private val reauthorizationClient: com.example.chatgpt_mealplanner.core.sync.follower.reauth.FollowerReauthorizationClient? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val keyAlias = "pantry_follower_key"

    open suspend fun processQrEnrollment(
        qrJsonString: String,
        currentTimeMs: Long = System.currentTimeMillis()
    ): EnrollmentResponseDto {
        val qrPayload = runCatching { json.decodeFromString<QrInvitationPayloadDto>(qrJsonString) }.getOrNull()
            ?: return EnrollmentResponseDto(
                success = false,
                householdId = "",
                errorCode = "MALFORMED_QR_PAYLOAD",
                errorMessage = "QR code content is not a valid JSON invitation"
            )

        if (!SyncContractValidation.validateQrInvitation(qrPayload, currentTimeMs)) {
            val errCode = when {
                qrPayload.protocolVersion != 1 -> "UNSUPPORTED_PROTOCOL_VERSION"
                qrPayload.expiresAt <= currentTimeMs -> "INVITATION_EXPIRED_LOCALLY"
                qrPayload.host.isBlank() -> "BLANK_HOST"
                !SyncContractValidation.isValidSha256Fingerprint(qrPayload.tlsCertificateFingerprint) -> "INVALID_TLS_FINGERPRINT"
                qrPayload.enrollmentToken.isBlank() -> "BLANK_TOKEN"
                else -> "INVALID_QR_INVITATION"
            }
            return EnrollmentResponseDto(
                success = false,
                householdId = qrPayload.householdId,
                errorCode = errCode,
                errorMessage = "QR invitation validation failed: $errCode"
            )
        }

        // 1. Obtain persistent identity - FAIL CLOSED if storage unavailable
        val followerDeviceId = identityStore.getOrCreateDeviceId()
            ?: return EnrollmentResponseDto(
                success = false,
                householdId = qrPayload.householdId,
                errorCode = "IDENTITY_STORAGE_UNAVAILABLE",
                errorMessage = "Encrypted identity store is unavailable or persistence failed"
            )

        keyStoreManager.ensureKeyPair(keyAlias)
        val publicKeyBase64 = keyStoreManager.getPublicKeyBase64(keyAlias)
            ?: return EnrollmentResponseDto(
                success = false,
                householdId = qrPayload.householdId,
                errorCode = "KEYSTORE_EXPORT_FAILED",
                errorMessage = "Failed to export public key from AndroidKeyStore"
            )

        // Delegate to Re-authorization Client if purpose is REAUTHORIZE_EXISTING_DEVICE!
        if (qrPayload.purpose == "REAUTHORIZE_EXISTING_DEVICE") {
            val client = reauthorizationClient ?: com.example.chatgpt_mealplanner.core.sync.follower.reauth.FollowerReauthorizationClient(followerPairingStore, keyStoreManager, identityStore)
            val reauthRes = client.performReauthorization(qrPayload)
            return when (reauthRes) {
                is com.example.chatgpt_mealplanner.core.sync.follower.reauth.ReauthorizationClientResult.Success -> {
                    val config = FollowerPairingConfig(
                        hostIp = qrPayload.host,
                        port = qrPayload.port,
                        pinnedCertFingerprint = qrPayload.tlsCertificateFingerprint,
                        deviceId = followerDeviceId,
                        sessionToken = null,
                        keyAlias = keyAlias,
                        householdId = qrPayload.householdId,
                        mainDeviceId = qrPayload.mainDeviceId
                    )
                    followerPairingStore.savePairingConfig(config)
                    followerPairingStore.clearRevoked()
                    followerSyncScheduler.scheduleSyncWorkNonBlocking(context, "REAUTHORIZATION_COMPLETE")

                    EnrollmentResponseDto(
                        success = true,
                        householdId = qrPayload.householdId
                    )
                }
                is com.example.chatgpt_mealplanner.core.sync.follower.reauth.ReauthorizationClientResult.Failure -> {
                    EnrollmentResponseDto(
                        success = false,
                        householdId = qrPayload.householdId,
                        errorCode = reauthRes.errorCode,
                        errorMessage = reauthRes.message
                    )
                }
            }
        }

        val enrollmentReq = EnrollmentRequestDto(
            enrollmentToken = qrPayload.enrollmentToken,
            householdId = qrPayload.householdId,
            mainDeviceId = qrPayload.mainDeviceId,
            followerDeviceId = followerDeviceId,
            followerDeviceName = android.os.Build.MODEL ?: "Follower Device",
            followerPublicKey = publicKeyBase64
        )

        // 2. Perform pinned TLS enrollment HTTP request
        val response = pantrySyncClient.registerEnrollment(
            hostIp = qrPayload.host,
            port = qrPayload.port,
            pinnedCertFingerprint = qrPayload.tlsCertificateFingerprint,
            requestDto = enrollmentReq
        )

        if (!response.success) {
            return response
        }

        if (response.householdId != qrPayload.householdId) {
            return EnrollmentResponseDto(
                success = false,
                householdId = qrPayload.householdId,
                errorCode = "HOUSEHOLD_MISMATCH",
                errorMessage = "Enrolled household ID does not match QR invitation"
            )
        }

        // 3. Commit FollowerPairingStore ONLY AFTER enrollment succeeds
        val config = FollowerPairingConfig(
            hostIp = qrPayload.host,
            port = qrPayload.port,
            pinnedCertFingerprint = qrPayload.tlsCertificateFingerprint,
            deviceId = followerDeviceId,
            sessionToken = null,
            keyAlias = keyAlias,
            householdId = qrPayload.householdId,
            mainDeviceId = qrPayload.mainDeviceId
        )

        val savedSuccessfully = followerPairingStore.savePairingConfig(config)
        if (!savedSuccessfully) {
            return EnrollmentResponseDto(
                success = false,
                householdId = qrPayload.householdId,
                errorCode = "PAIRING_STORE_PERSISTENCE_FAILED",
                errorMessage = "Failed to store pairing configuration in encrypted preferences"
            )
        }

        // 4. Schedule existing sync worker for ongoing background sync
        followerSyncScheduler.scheduleSyncWorkNonBlocking(context, "ENROLLMENT_COMPLETE")

        return response
    }
}
