package com.example.chatgpt_mealplanner.core.sync.client

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier
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
import kotlinx.serialization.json.contentOrNull
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

sealed interface AuthTokenResult {
    data class Success(val token: String) : AuthTokenResult
    data class AuthFailure(val errorCode: String, val message: String) : AuthTokenResult
    data class ServerFailure(val errorCode: String, val message: String) : AuthTokenResult
    data class TransportFailure(val message: String) : AuthTokenResult
    object Revoked : AuthTokenResult
}

enum class NetworkResultStatus {
    SUCCESS,
    UNAUTHORIZED,
    SERVER_ERROR,
    TRANSPORT_ERROR,
    REVOKED
}

data class TestClientSyncResult(
    val success: Boolean,
    val authenticated: Boolean,
    val responseJson: String?,
    val error: String? = null
)

data class TestClientOperationResult(
    val success: Boolean = true,
    val resultDto: SyncOperationResultDto? = null,
    val error: String? = null,
    val status: NetworkResultStatus = if (success) NetworkResultStatus.SUCCESS else if (error == "FOLLOWER_REVOKED") NetworkResultStatus.REVOKED else if (error?.contains("401") == true || error?.contains("UNAUTHORIZED") == true || error?.contains("EXPIRED") == true) NetworkResultStatus.UNAUTHORIZED else NetworkResultStatus.SERVER_ERROR
)

data class TestClientFeedResult(
    val success: Boolean = true,
    val feedResponse: SyncFeedResponseDto? = null,
    val error: String? = null,
    val status: NetworkResultStatus = if (success) NetworkResultStatus.SUCCESS else if (error == "FOLLOWER_REVOKED") NetworkResultStatus.REVOKED else if (error?.contains("401") == true || error?.contains("UNAUTHORIZED") == true || error?.contains("EXPIRED") == true) NetworkResultStatus.UNAUTHORIZED else NetworkResultStatus.SERVER_ERROR
)

data class TestClientBootstrapResult(
    val success: Boolean = true,
    val bootstrapResponse: BootstrapResponseDto? = null,
    val error: String? = null,
    val status: NetworkResultStatus = if (success) NetworkResultStatus.SUCCESS else if (error == "FOLLOWER_REVOKED") NetworkResultStatus.REVOKED else if (error?.contains("401") == true || error?.contains("UNAUTHORIZED") == true || error?.contains("EXPIRED") == true) NetworkResultStatus.UNAUTHORIZED else NetworkResultStatus.SERVER_ERROR
)

@Singleton
open class PantrySyncTestClient @Inject constructor(
    private val keyStoreManager: AndroidKeyStoreManager,
    private val followerPairingStore: com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    open fun createPinnedOkHttpClient(pinnedCertFingerprint: String?): OkHttpClient {
        require(com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation.isValidSha256Fingerprint(pinnedCertFingerprint)) {
            "Missing, blank, or malformed TLS certificate fingerprint"
        }
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
                if (!actualFingerprint.equals(pinnedCertFingerprint?.trim(), ignoreCase = true)) {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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

            if (!response.isSuccessful) {
                if (FollowerErrorClassifier.isExplicitRevocation(responseBodyText)) {
                    followerPairingStore?.markRevoked()
                    return@withContext TestClientOperationResult(status = NetworkResultStatus.REVOKED, error = "FOLLOWER_REVOKED")
                }
                val status = if (response.code in listOf(401, 403)) NetworkResultStatus.UNAUTHORIZED else NetworkResultStatus.SERVER_ERROR
                return@withContext TestClientOperationResult(status = status, error = "HTTP ${response.code}: $responseBodyText")
            }

            val resultDto = runCatching { json.decodeFromString<SyncOperationResultDto>(responseBodyText) }.getOrNull()
            if (resultDto != null) {
                TestClientOperationResult(status = NetworkResultStatus.SUCCESS, resultDto = resultDto)
            } else {
                TestClientOperationResult(status = NetworkResultStatus.SERVER_ERROR, error = "Malformed response JSON")
            }
        } catch (e: java.io.IOException) {
            TestClientOperationResult(status = NetworkResultStatus.TRANSPORT_ERROR, error = e.message ?: "Network transport error")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TestClientOperationResult(status = NetworkResultStatus.SERVER_ERROR, error = e.message ?: "Execution error")
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

            if (!response.isSuccessful) {
                if (FollowerErrorClassifier.isExplicitRevocation(responseBodyText)) {
                    followerPairingStore?.markRevoked()
                    return@withContext TestClientFeedResult(status = NetworkResultStatus.REVOKED, error = "FOLLOWER_REVOKED")
                }
                val status = if (response.code in listOf(401, 403)) NetworkResultStatus.UNAUTHORIZED else NetworkResultStatus.SERVER_ERROR
                return@withContext TestClientFeedResult(status = status, error = "HTTP ${response.code}: $responseBodyText")
            }

            val feedDto = runCatching { json.decodeFromString<SyncFeedResponseDto>(responseBodyText) }.getOrNull()
            if (feedDto != null) {
                TestClientFeedResult(status = NetworkResultStatus.SUCCESS, feedResponse = feedDto)
            } else {
                TestClientFeedResult(status = NetworkResultStatus.SERVER_ERROR, error = "Malformed response JSON")
            }
        } catch (e: java.io.IOException) {
            TestClientFeedResult(status = NetworkResultStatus.TRANSPORT_ERROR, error = e.message ?: "Network transport error")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TestClientFeedResult(status = NetworkResultStatus.SERVER_ERROR, error = e.message ?: "Execution error")
        }
    }

    /** Follower: asks the main phone for the household's AI key. Null if it is not available right now. */
    open suspend fun getAiAccessToken(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val client = createPinnedOkHttpClient(pinnedCertFingerprint)
            val request = Request.Builder()
                .url("https://$hostIp:$port/sync/v1/ai-access")
                .header("X-Pantry-Session-Token", sessionToken)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["token"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
    }

    open suspend fun getBootstrapSnapshot(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String
    ): TestClientBootstrapResult = withContext(Dispatchers.IO) {
        val client = try {
            createPinnedOkHttpClient(pinnedCertFingerprint)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext TestClientBootstrapResult(status = NetworkResultStatus.TRANSPORT_ERROR, error = "TLS_ERROR")
        }

        val baseUrl = "https://$hostIp:$port"
        val request = Request.Builder()
            .url("$baseUrl/sync/v1/bootstrap")
            .header("X-Pantry-Session-Token", sessionToken)
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            return@withContext TestClientBootstrapResult(status = NetworkResultStatus.TRANSPORT_ERROR, error = "NETWORK_ERROR")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext TestClientBootstrapResult(status = NetworkResultStatus.SERVER_ERROR, error = "SERVER_ERROR")
        }

        val responseBodyText = response.body?.string() ?: "{}"
        if (!response.isSuccessful) {
            if (FollowerErrorClassifier.isExplicitRevocation(responseBodyText)) {
                followerPairingStore?.markRevoked()
                return@withContext TestClientBootstrapResult(status = NetworkResultStatus.REVOKED, error = "FOLLOWER_REVOKED")
            }
            return@withContext if (response.code in listOf(401, 403)) {
                TestClientBootstrapResult(status = NetworkResultStatus.UNAUTHORIZED, error = "UNAUTHORIZED")
            } else {
                TestClientBootstrapResult(status = NetworkResultStatus.SERVER_ERROR, error = "SERVER_ERROR")
            }
        }

        val bootstrapDto = runCatching { json.decodeFromString<BootstrapResponseDto>(responseBodyText) }.getOrNull()
        if (bootstrapDto != null) {
            TestClientBootstrapResult(status = NetworkResultStatus.SUCCESS, bootstrapResponse = bootstrapDto)
        } else {
            TestClientBootstrapResult(status = NetworkResultStatus.SERVER_ERROR, error = "SERVER_ERROR")
        }
    }

    open suspend fun obtainSessionTokenResult(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        deviceId: String,
        keyAlias: String = "pantry_follower_key"
    ): AuthTokenResult = withContext(Dispatchers.IO) {
        val client = try {
            createPinnedOkHttpClient(pinnedCertFingerprint)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext AuthTokenResult.TransportFailure(e.message ?: "TLS setup error")
        }

        val baseUrl = "https://$hostIp:$port"

        val challengeResponse = try {
            val challengeBody = json.encodeToString(buildJsonObject { put("deviceId", deviceId) }).toRequestBody("application/json".toMediaType())
            val challengeRequest = Request.Builder().url("$baseUrl/sync/v1/auth/challenge").post(challengeBody).build()
            client.newCall(challengeRequest).execute()
        } catch (e: java.io.IOException) {
            return@withContext AuthTokenResult.TransportFailure(e.message ?: "Network transport error during challenge")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext AuthTokenResult.ServerFailure("CHALLENGE_EXEC_ERROR", e.message ?: "Challenge execution error")
        }

        val challengeResponseBody = challengeResponse.body?.string() ?: ""
        if (!challengeResponse.isSuccessful) {
            if (FollowerErrorClassifier.isExplicitRevocation(challengeResponseBody)) {
                followerPairingStore?.markRevoked()
                return@withContext AuthTokenResult.Revoked
            }
            return@withContext if (challengeResponse.code in listOf(401, 403)) {
                AuthTokenResult.AuthFailure("CHALLENGE_REJECTED", "Auth challenge rejected by server (HTTP ${challengeResponse.code})")
            } else {
                AuthTokenResult.ServerFailure("HTTP_${challengeResponse.code}", "Server error during challenge (HTTP ${challengeResponse.code})")
            }
        }

        val challengeDto = runCatching { json.decodeFromString<AuthChallengeDto>(challengeResponseBody) }.getOrNull()
            ?: return@withContext AuthTokenResult.ServerFailure("MALFORMED_CHALLENGE_RESPONSE", "Malformed challenge response JSON")

        val canonicalBytes = "pantry-household-sync|1|${challengeDto.householdId}|$deviceId|${challengeDto.challengeId}|${challengeDto.nonce}".toByteArray(Charsets.UTF_8)
        val signatureBase64 = keyStoreManager.signChallenge(keyAlias, canonicalBytes)

        val sessionReqDto = AuthChallengeResponseDto(
            challengeId = challengeDto.challengeId,
            deviceId = deviceId,
            signature = signatureBase64
        )

        val sessionResponse = try {
            val sessionBody = json.encodeToString(AuthChallengeResponseDto.serializer(), sessionReqDto).toRequestBody("application/json".toMediaType())
            val sessionRequest = Request.Builder().url("$baseUrl/sync/v1/auth/session").post(sessionBody).build()
            client.newCall(sessionRequest).execute()
        } catch (e: java.io.IOException) {
            return@withContext AuthTokenResult.TransportFailure(e.message ?: "Network transport error during session request")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext AuthTokenResult.ServerFailure("SESSION_EXEC_ERROR", e.message ?: "Session execution error")
        }

        val sessionResponseBody = sessionResponse.body?.string() ?: ""
        if (!sessionResponse.isSuccessful) {
            if (com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerErrorClassifier.isExplicitRevocation(sessionResponseBody)) {
                followerPairingStore?.markRevoked()
                return@withContext AuthTokenResult.Revoked
            }
            return@withContext if (sessionResponse.code in listOf(401, 403)) {
                AuthTokenResult.AuthFailure("SESSION_REJECTED", "Session creation rejected by server (HTTP ${sessionResponse.code})")
            } else {
                AuthTokenResult.ServerFailure("HTTP_${sessionResponse.code}", "Server error during session creation (HTTP ${sessionResponse.code})")
            }
        }

        val sessionObj = runCatching { json.parseToJsonElement(sessionResponseBody).jsonObject }.getOrNull()
            ?: return@withContext AuthTokenResult.ServerFailure("MALFORMED_SESSION_RESPONSE", "Malformed session response JSON")

        val token = sessionObj["sessionToken"]?.jsonPrimitive?.content
        if (token != null) {
            AuthTokenResult.Success(token)
        } else {
            AuthTokenResult.ServerFailure("MISSING_TOKEN", "Session token missing in server response")
        }
    }

    open suspend fun obtainSessionToken(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        deviceId: String,
        keyAlias: String = "pantry_follower_key"
    ): String? {
        return when (val res = obtainSessionTokenResult(hostIp, port, pinnedCertFingerprint, deviceId, keyAlias)) {
            is AuthTokenResult.Success -> res.token
            else -> null
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
