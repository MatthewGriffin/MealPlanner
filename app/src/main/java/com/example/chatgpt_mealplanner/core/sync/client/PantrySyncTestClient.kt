package com.example.chatgpt_mealplanner.core.sync.client

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.sync.contract.AuthChallengeDto
import com.example.chatgpt_mealplanner.sync.contract.AuthChallengeResponseDto
import com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationResultDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class TestClientSyncResult(
    val success: Boolean,
    val authenticated: Boolean,
    val responseJson: String?,
    val error: String? = null
)

data class TestClientOperationResult(
    val success: Boolean,
    val resultDto: SyncOperationResultDto? = null,
    val error: String? = null
)

data class TestClientFeedResult(
    val success: Boolean,
    val feedResponse: SyncFeedResponseDto? = null,
    val error: String? = null
)

data class TestClientBootstrapResult(
    val success: Boolean,
    val bootstrapResponse: BootstrapResponseDto? = null,
    val error: String? = null
)

@Singleton
open class PantrySyncTestClient @Inject constructor(
    private val keyStoreManager: AndroidKeyStoreManager,
    private val followerPairingStore: com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    open fun createPinnedOkHttpClient(pinnedCertFingerprint: String?): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Empty certificate chain")
                val serverCert = chain[0]
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(serverCert.encoded)
                val sb = StringBuilder("SHA256:")
                for (i in digest.indices) {
                    sb.append(String.format("%02X", digest[i]))
                    if (i < digest.size - 1) sb.append(":")
                }
                val actualFingerprint = sb.toString()
                if (!pinnedCertFingerprint.isNullOrBlank() && !actualFingerprint.equals(pinnedCertFingerprint, ignoreCase = true)) {
                    throw java.security.cert.CertificateException("TLS Certificate Pin Mismatch! Expected: $pinnedCertFingerprint, Actual: $actualFingerprint")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // IP address host binding verification allowed when pinned
            .build()
    }

    open suspend fun executeFullAuthSpike(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        deviceId: String,
        keyAlias: String = "pantry_test_follower_key"
    ): TestClientSyncResult = withContext(Dispatchers.IO) {
        try {
            val client = createPinnedOkHttpClient(pinnedCertFingerprint)
            val baseUrl = "https://$hostIp:$port"

            // 1. Request Challenge
            val challengeReqObj = buildJsonObject { put("deviceId", deviceId) }
            val challengeBody = json.encodeToString(buildJsonObject { put("deviceId", deviceId) }).toRequestBody("application/json".toMediaType())
            val challengeRequest = Request.Builder().url("$baseUrl/sync/v1/auth/challenge").post(challengeBody).build()

            val challengeResponse = client.newCall(challengeRequest).execute()
            if (!challengeResponse.isSuccessful) {
                return@withContext TestClientSyncResult(false, false, null, "Challenge request failed HTTP ${challengeResponse.code}")
            }

            val challengeDto = json.decodeFromString<AuthChallengeDto>(challengeResponse.body?.string() ?: "{}")

            // 2. Sign Challenge Canonical Bytes
            val canonicalBytes = "pantry-household-sync|1|${challengeDto.householdId}|$deviceId|${challengeDto.challengeId}|${challengeDto.nonce}".toByteArray(Charsets.UTF_8)
            val signatureBase64 = keyStoreManager.signChallenge(keyAlias, canonicalBytes)

            // 3. Request Session
            val sessionReqDto = AuthChallengeResponseDto(
                challengeId = challengeDto.challengeId,
                deviceId = deviceId,
                signature = signatureBase64
            )
            val sessionBody = json.encodeToString(AuthChallengeResponseDto.serializer(), sessionReqDto).toRequestBody("application/json".toMediaType())
            val sessionRequest = Request.Builder().url("$baseUrl/sync/v1/auth/session").post(sessionBody).build()

            val sessionResponse = client.newCall(sessionRequest).execute()
            if (!sessionResponse.isSuccessful) {
                return@withContext TestClientSyncResult(false, false, null, "Session request failed HTTP ${sessionResponse.code}")
            }

            val sessionResponseBody = sessionResponse.body?.string() ?: "{}"
            val sessionObj = json.parseToJsonElement(sessionResponseBody).jsonObject
            val token = sessionObj["sessionToken"]?.jsonPrimitive?.content
                ?: return@withContext TestClientSyncResult(false, false, null, "Session token missing from response")

            // 4. Authenticated Ping
            val pingRequest = Request.Builder()
                .url("$baseUrl/sync/v1/authenticated-ping")
                .header("X-Pantry-Session-Token", token)
                .get()
                .build()

            val pingResponse = client.newCall(pingRequest).execute()
            val pingResponseBody = pingResponse.body?.string() ?: "{}"

            if (pingResponse.isSuccessful) {
                TestClientSyncResult(true, true, pingResponseBody, null)
            } else {
                TestClientSyncResult(false, false, pingResponseBody, "Ping failed HTTP ${pingResponse.code}")
            }
        } catch (e: Exception) {
            TestClientSyncResult(false, false, null, "Spike error: ${e.message}")
        }
    }

    open suspend fun submitSyncOperation(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        operationDto: SyncOperationDto
    ): TestClientOperationResult = withContext(Dispatchers.IO) {
        try {
            val client = createPinnedOkHttpClient(pinnedCertFingerprint)
            val baseUrl = "https://$hostIp:$port"

            val bodyText = json.encodeToString(SyncOperationDto.serializer(), operationDto)
            val body = bodyText.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/sync/v1/operations")
                .header("X-Pantry-Session-Token", sessionToken)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBodyText = response.body?.string() ?: "{}"

            val resultDto = runCatching { json.decodeFromString<SyncOperationResultDto>(responseBodyText) }.getOrNull()

            if (response.isSuccessful && resultDto != null) {
                TestClientOperationResult(true, resultDto, null)
            } else {
                TestClientOperationResult(false, resultDto, "HTTP ${response.code}: $responseBodyText")
            }
        } catch (e: Exception) {
            TestClientOperationResult(false, null, e.message ?: "Network error")
        }
    }

    open suspend fun getSyncFeed(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        sinceSequence: Long = 0L,
        limit: Int = 100
    ): TestClientFeedResult = withContext(Dispatchers.IO) {
        try {
            val client = createPinnedOkHttpClient(pinnedCertFingerprint)
            val baseUrl = "https://$hostIp:$port"

            val request = Request.Builder()
                .url("$baseUrl/sync/v1/feed?sinceSequence=$sinceSequence&limit=$limit")
                .header("X-Pantry-Session-Token", sessionToken)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBodyText = response.body?.string() ?: "{}"

            val feedDto = runCatching { json.decodeFromString<SyncFeedResponseDto>(responseBodyText) }.getOrNull()

            if (response.isSuccessful && feedDto != null) {
                TestClientFeedResult(true, feedDto, null)
            } else {
                TestClientFeedResult(false, feedDto, "HTTP ${response.code}: $responseBodyText")
            }
        } catch (e: Exception) {
            TestClientFeedResult(false, null, e.message ?: "Network error")
        }
    }

    open suspend fun getBootstrapSnapshot(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String
    ): TestClientBootstrapResult = withContext(Dispatchers.IO) {
        try {
            val client = createPinnedOkHttpClient(pinnedCertFingerprint)
            val baseUrl = "https://$hostIp:$port"

            val request = Request.Builder()
                .url("$baseUrl/sync/v1/bootstrap")
                .header("X-Pantry-Session-Token", sessionToken)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBodyText = response.body?.string() ?: "{}"

            val bootstrapDto = runCatching { json.decodeFromString<BootstrapResponseDto>(responseBodyText) }.getOrNull()

            if (response.isSuccessful && bootstrapDto != null) {
                TestClientBootstrapResult(true, bootstrapDto, null)
            } else {
                TestClientBootstrapResult(false, bootstrapDto, "HTTP ${response.code}: $responseBodyText")
            }
        } catch (e: Exception) {
            TestClientBootstrapResult(false, null, e.message ?: "Network error")
        }
    }

    open suspend fun obtainSessionToken(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        deviceId: String,
        keyAlias: String = "pantry_follower_key"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val client = createPinnedOkHttpClient(pinnedCertFingerprint)
            val baseUrl = "https://$hostIp:$port"

            val challengeBody = json.encodeToString(buildJsonObject { put("deviceId", deviceId) }).toRequestBody("application/json".toMediaType())
            val challengeRequest = Request.Builder().url("$baseUrl/sync/v1/auth/challenge").post(challengeBody).build()
            val challengeResponse = client.newCall(challengeRequest).execute()
            if (!challengeResponse.isSuccessful) return@withContext null

            val challengeDto = json.decodeFromString<AuthChallengeDto>(challengeResponse.body?.string() ?: "{}")
            val canonicalBytes = "pantry-household-sync|1|${challengeDto.householdId}|$deviceId|${challengeDto.challengeId}|${challengeDto.nonce}".toByteArray(Charsets.UTF_8)
            val signatureBase64 = keyStoreManager.signChallenge(keyAlias, canonicalBytes)

            val sessionReqDto = AuthChallengeResponseDto(
                challengeId = challengeDto.challengeId,
                deviceId = deviceId,
                signature = signatureBase64
            )
            val sessionBody = json.encodeToString(AuthChallengeResponseDto.serializer(), sessionReqDto).toRequestBody("application/json".toMediaType())
            val sessionRequest = Request.Builder().url("$baseUrl/sync/v1/auth/session").post(sessionBody).build()

            val sessionResponse = client.newCall(sessionRequest).execute()
            val sessionResponseBody = sessionResponse.body?.string() ?: "{}"
            if (!sessionResponse.isSuccessful) {
                if (com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation(sessionResponseBody)) {
                    followerPairingStore?.markRevoked()
                }
                return@withContext null
            }

            val sessionObj = json.parseToJsonElement(sessionResponseBody).jsonObject
            sessionObj["sessionToken"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    open suspend fun registerEnrollment(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String,
        requestDto: com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto
    ): com.example.chatgpt_mealplanner.sync.contract.EnrollmentResponseDto = withContext(Dispatchers.IO) {
        if (pinnedCertFingerprint.isBlank()) {
            return@withContext com.example.chatgpt_mealplanner.sync.contract.EnrollmentResponseDto(
                success = false,
                householdId = requestDto.householdId,
                errorCode = "BLANK_PINNED_FINGERPRINT",
                errorMessage = "TLS certificate fingerprint cannot be blank"
            )
        }

        try {
            val client = createPinnedOkHttpClient(pinnedCertFingerprint)
            val baseUrl = "https://$hostIp:$port"

            val bodyJson = json.encodeToString(com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto.serializer(), requestDto)
            val reqBody = bodyJson.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/sync/v1/enrollment/register")
                .post(reqBody)
                .build()

            val response = client.newCall(request).execute()
            val respBodyStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                json.decodeFromString<com.example.chatgpt_mealplanner.sync.contract.EnrollmentResponseDto>(respBodyStr)
            } else {
                val parsedErr = runCatching { json.decodeFromString<com.example.chatgpt_mealplanner.sync.contract.EnrollmentResponseDto>(respBodyStr) }.getOrNull()
                parsedErr ?: com.example.chatgpt_mealplanner.sync.contract.EnrollmentResponseDto(
                    success = false,
                    householdId = requestDto.householdId,
                    errorCode = "HTTP_${response.code}",
                    errorMessage = response.message
                )
            }
        } catch (e: Exception) {
            com.example.chatgpt_mealplanner.sync.contract.EnrollmentResponseDto(
                success = false,
                householdId = requestDto.householdId,
                errorCode = "NETWORK_ERROR",
                errorMessage = e.message ?: "Network error during enrollment"
            )
        }
    }
}
