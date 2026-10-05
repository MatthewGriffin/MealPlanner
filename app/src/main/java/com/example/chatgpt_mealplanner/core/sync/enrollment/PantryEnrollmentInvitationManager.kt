package com.example.chatgpt_mealplanner.core.sync.enrollment

import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentResponseDto
import com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class EphemeralInvitation(
    val enrollmentToken: String,
    val householdId: String,
    val mainDeviceId: String,
    val expiresAt: Long,
    var isConsumed: Boolean = false,
    var claimedByDeviceId: String? = null
)

@Singleton
open class PantryEnrollmentInvitationManager @Inject constructor(
    private val pairedDeviceDao: PairedDeviceDao,
    private val keyStoreManager: com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
) {
    private val mutex = Mutex()
    private val invitations = ConcurrentHashMap<String, EphemeralInvitation>()
    private val secureRandom = SecureRandom()

    companion object {
        const val DEFAULT_EXPIRY_MS = 300_000L // 5 minutes
    }

    open suspend fun createInvitation(
        householdId: String,
        mainDeviceId: String,
        hostIp: String,
        port: Int = 8443,
        tlsFingerprint: String,
        expiryMs: Long = DEFAULT_EXPIRY_MS
    ): QrInvitationPayloadDto = mutex.withLock {
        val tokenBytes = ByteArray(16) // 128 bits entropy
        secureRandom.nextBytes(tokenBytes)
        val tokenHex = tokenBytes.joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val expiresAt = now + expiryMs

        val invitation = EphemeralInvitation(
            enrollmentToken = tokenHex,
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            expiresAt = expiresAt
        )
        invitations[tokenHex] = invitation

        QrInvitationPayloadDto(
            protocolVersion = 1,
            householdId = householdId,
            mainDeviceId = mainDeviceId,
            host = hostIp,
            port = port,
            tlsCertificateFingerprint = tlsFingerprint,
            enrollmentToken = tokenHex,
            expiresAt = expiresAt
        )
    }

    open suspend fun processEnrollment(
        request: EnrollmentRequestDto,
        expectedHouseholdId: String,
        expectedMainDeviceId: String,
        currentTimeMs: Long = System.currentTimeMillis()
    ): EnrollmentResponseDto = mutex.withLock {
        if (!SyncContractValidation.validateEnrollmentRequest(request)) {
            return EnrollmentResponseDto(
                success = false,
                householdId = expectedHouseholdId,
                errorCode = "INVALID_ENROLLMENT_PAYLOAD",
                errorMessage = "Enrollment request payload failed validation"
            )
        }

        if (!keyStoreManager.isValidP256PublicKey(request.followerPublicKey)) {
            return EnrollmentResponseDto(
                success = false,
                householdId = expectedHouseholdId,
                errorCode = "INVALID_PUBLIC_KEY",
                errorMessage = "Follower public key is not a parseable supported EC P-256 public key"
            )
        }

        if (request.householdId != expectedHouseholdId || request.mainDeviceId != expectedMainDeviceId) {
            return EnrollmentResponseDto(
                success = false,
                householdId = expectedHouseholdId,
                errorCode = "IDENTITY_MISMATCH",
                errorMessage = "Household or Main device ID mismatch"
            )
        }

        // Token authorization MUST occur FIRST before any ACTIVE device check!
        val invitation = invitations[request.enrollmentToken]
            ?: return EnrollmentResponseDto(
                success = false,
                householdId = expectedHouseholdId,
                errorCode = "INVITATION_NOT_FOUND",
                errorMessage = "Enrollment token is invalid or process restarted"
            )

        if (invitation.householdId != expectedHouseholdId || invitation.mainDeviceId != expectedMainDeviceId) {
            return EnrollmentResponseDto(
                success = false,
                householdId = expectedHouseholdId,
                errorCode = "INVITATION_IDENTITY_MISMATCH",
                errorMessage = "Invitation household or Main device ID mismatch"
            )
        }

        if (currentTimeMs >= invitation.expiresAt) {
            invitations.remove(request.enrollmentToken)
            return EnrollmentResponseDto(
                success = false,
                householdId = expectedHouseholdId,
                errorCode = "INVITATION_EXPIRED",
                errorMessage = "Enrollment invitation has expired"
            )
        }

        if (invitation.isConsumed) {
            if (invitation.claimedByDeviceId == request.followerDeviceId) {
                val existingDevice = pairedDeviceDao.getDeviceById(request.followerDeviceId)
                if (existingDevice != null && existingDevice.devicePublicKey == request.followerPublicKey && existingDevice.status == "ACTIVE") {
                    return EnrollmentResponseDto(
                        success = true,
                        householdId = expectedHouseholdId
                    )
                }
            }
            return EnrollmentResponseDto(
                success = false,
                householdId = expectedHouseholdId,
                errorCode = "INVITATION_ALREADY_CONSUMED",
                errorMessage = "Enrollment invitation has already been used by another device"
            )
        }

        val existingDevice = pairedDeviceDao.getDeviceById(request.followerDeviceId)
        if (existingDevice != null) {
            if (existingDevice.devicePublicKey != request.followerPublicKey) {
                return EnrollmentResponseDto(
                    success = false,
                    householdId = expectedHouseholdId,
                    errorCode = "DEVICE_ID_CONFLICT",
                    errorMessage = "Follower device ID is already bound to a different public key"
                )
            }
            if (existingDevice.status == "REVOKED") {
                return EnrollmentResponseDto(
                    success = false,
                    householdId = expectedHouseholdId,
                    errorCode = "FOLLOWER_REVOKED",
                    errorMessage = "Follower device has been revoked by Main"
                )
            }
        }

        // Claim invitation atomically before DB insertion attempt
        invitation.claimedByDeviceId = request.followerDeviceId

        return try {
            val newDevice = PairedDeviceEntity(
                deviceId = request.followerDeviceId,
                deviceName = request.followerDeviceName.take(64).trim(),
                devicePublicKey = request.followerPublicKey,
                status = "ACTIVE",
                pairedAt = currentTimeMs,
                lastSyncTimestamp = 0L
            )
            pairedDeviceDao.insertOrUpdateDevice(newDevice)

            // Registration succeeded: Mark invitation consumed permanently
            invitation.isConsumed = true
            EnrollmentResponseDto(
                success = true,
                householdId = expectedHouseholdId
            )
        } catch (e: Exception) {
            // Registration failed: Release claim without marking invitation consumed
            invitation.claimedByDeviceId = null
            EnrollmentResponseDto(
                success = false,
                householdId = expectedHouseholdId,
                errorCode = "REGISTRATION_FAILED",
                errorMessage = "Failed to store paired device: ${e.message}"
            )
        }
    }

    open fun clear() {
        invitations.clear()
    }
}
