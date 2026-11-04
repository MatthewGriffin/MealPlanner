package com.example.chatgpt_mealplanner.core.sync.follower.reauth

import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.sync.contract.QrInvitationPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.ReauthorizationRequestDto
import com.example.chatgpt_mealplanner.sync.contract.ReauthorizationResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface ReauthorizationClientResult {
    object Success : ReauthorizationClientResult
    data class Failure(val errorCode: String, val message: String) : ReauthorizationClientResult
}

@Singleton
open class FollowerReauthorizationClient @Inject constructor(
    private val followerPairingStore: FollowerPairingStore,
    private val keyStoreManager: AndroidKeyStoreManager,
    private val identityStore: com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun createPinnedOkHttpClient(pinnedCertFingerprint: String): OkHttpClient {
        if (!SyncContractValidation.isValidSha256Fingerprint(pinnedCertFingerprint)) {
            throw IllegalArgumentException("Missing, blank, or malformed TLS certificate fingerprint: $pinnedCertFingerprint")
        }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Server certificate chain is empty")
                val serverCert = chain[0]
                val sha256Digest = MessageDigest.getInstance("SHA-256").digest(serverCert.encoded)
                val fingerprint = "SHA256:" + sha256Digest.joinToString(":") { "%02X".format(it) }

                val expected = pinnedCertFingerprint.trim().uppercase()
                if (fingerprint.uppercase() != expected) {
                    throw java.security.cert.CertificateException("TLS Certificate Fingerprint Mismatch. Expected: $expected, Actual: $fingerprint")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())

        val hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            try {
                val certs = session.peerCertificates
                if (certs.isNullOrEmpty()) return@HostnameVerifier false
                val serverCert = certs[0] as? X509Certificate ?: return@HostnameVerifier false
                val sha256Digest = MessageDigest.getInstance("SHA-256").digest(serverCert.encoded)
                val fingerprint = "SHA256:" + sha256Digest.joinToString(":") { "%02X".format(it) }
                fingerprint.uppercase() == pinnedCertFingerprint.trim().uppercase()
            } catch (_: Exception) {
                false
            }
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .build()
    }

    open suspend fun performReauthorization(
        invitation: QrInvitationPayloadDto
    ): ReauthorizationClientResult = withContext(Dispatchers.IO) {
        if (!SyncContractValidation.isValidSha256Fingerprint(invitation.tlsCertificateFingerprint)) {
            return@withContext ReauthorizationClientResult.Failure("INVALID_TLS_PIN", "Missing, blank, or malformed TLS certificate fingerprint in invitation")
        }

        if (invitation.purpose != "REAUTHORIZE_EXISTING_DEVICE") {
            return@withContext ReauthorizationClientResult.Failure("INVALID_PURPOSE", "Ordinary enrollment invitation cannot be used for re-authorization")
        }

        val config = followerPairingStore.getPairingConfig()
        val localDeviceId = config?.deviceId ?: identityStore?.getOrCreateDeviceId()
            ?: return@withContext ReauthorizationClientResult.Failure("NO_DEVICE_ID", "No local follower device ID found")

        val keyAlias = config?.keyAlias ?: "pantry_follower_key"

        if (invitation.targetDeviceId != null && invitation.targetDeviceId != localDeviceId) {
            return@withContext ReauthorizationClientResult.Failure("TARGET_DEVICE_MISMATCH", "Re-authorization invitation is bound to a different follower device ID")
        }

        val canonicalBytes = com.example.chatgpt_mealplanner.sync.contract.ReauthorizationCanonicalPayload.buildPayload(
            protocolVersion = 1,
            purpose = invitation.purpose,
            householdId = invitation.householdId,
            mainDeviceId = invitation.mainDeviceId,
            followerDeviceId = localDeviceId,
            enrollmentToken = invitation.enrollmentToken
        )
        val signatureBase64 = try {
            keyStoreManager.signChallenge(keyAlias, canonicalBytes)
        } catch (e: Exception) {
            rethrowIfCancelled()
            return@withContext ReauthorizationClientResult.Failure("SIGNATURE_FAILED", "Failed to sign re-authorization challenge with existing key")
        }

        val reqDto = ReauthorizationRequestDto(
            enrollmentToken = invitation.enrollmentToken,
            householdId = invitation.householdId,
            mainDeviceId = invitation.mainDeviceId,
            followerDeviceId = localDeviceId,
            signature = signatureBase64
        )

        val client = try {
            createPinnedOkHttpClient(invitation.tlsCertificateFingerprint)
        } catch (e: Exception) {
            rethrowIfCancelled()
            return@withContext ReauthorizationClientResult.Failure("TLS_SETUP_FAILED", e.message ?: "TLS setup error")
        }

        val baseUrl = "https://${invitation.host}:${invitation.port}"
        val reqBody = json.encodeToString(ReauthorizationRequestDto.serializer(), reqDto).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/sync/v1/reauthorize").post(reqBody).build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            return@withContext ReauthorizationClientResult.Failure("NETWORK_ERROR", e.message ?: "Network error during re-authorization")
        } catch (e: Exception) {
            rethrowIfCancelled()
            return@withContext ReauthorizationClientResult.Failure("SERVER_ERROR", e.message ?: "Server error during re-authorization")
        }

        val responseBodyText = response.body?.string() ?: "{}"
        val responseDto = runCatching { json.decodeFromString<ReauthorizationResponseDto>(responseBodyText) }.getOrNull()

        if (response.isSuccessful && responseDto?.success == true) {
            if (responseDto.householdId != invitation.householdId) {
                return@withContext ReauthorizationClientResult.Failure("RESPONSE_HOUSEHOLD_MISMATCH", "Response household ID mismatch")
            }
            if (responseDto.followerDeviceId != localDeviceId) {
                return@withContext ReauthorizationClientResult.Failure("RESPONSE_DEVICE_MISMATCH", "Response follower device ID mismatch")
            }
            // All identity checks passed over pinned TLS: Clear durable revoked marker!
            followerPairingStore.clearRevoked()
            ReauthorizationClientResult.Success
        } else {
            val errCode = responseDto?.errorCode ?: "HTTP_${response.code}"
            val errMsg = responseDto?.errorMessage ?: "Re-authorization request failed"
            ReauthorizationClientResult.Failure(errCode, errMsg)
        }
    }
}
