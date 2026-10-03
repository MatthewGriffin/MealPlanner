package com.example.chatgpt_mealplanner.core.sync.client

import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.sync.contract.AuthChallengeDto
import com.example.chatgpt_mealplanner.sync.contract.AuthChallengeResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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

@Singleton
open class PantrySyncTestClient @Inject constructor(
    private val keyStoreManager: AndroidKeyStoreManager
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
            val challengeBody = json.encodeToString(challengeReqObj).toRequestBody("application/json".toMediaType())
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
            val sessionBody = json.encodeToString(sessionReqDto).toRequestBody("application/json".toMediaType())
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
}
