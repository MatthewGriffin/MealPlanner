package com.example.chatgpt_mealplanner.core.sync.server

import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface HostStartResult {
    object Success : HostStartResult
    object AlreadyRunning : HostStartResult
    object NotEligibleFollower : HostStartResult
    data class Failed(val message: String) : HostStartResult
}

@kotlinx.serialization.Serializable
data class HealthResponseDto(
    val service: String,
    val protocolVersion: Int,
    val status: String
)

@Singleton
open class PantrySyncHostController @Inject constructor(
    private val pantrySyncServer: PantrySyncServer,
    private val followerPairingStore: FollowerPairingStore,
    private val appRoleResolver: com.example.chatgpt_mealplanner.core.sync.AppRoleResolver? = null
) {
    private val startLock = Any()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    open fun isEligibleToHost(): Boolean {
        // Corrupted or inaccessible store fails closed
        if (followerPairingStore.isStoreInaccessible()) {
            return false
        }
        val isFollower = appRoleResolver?.isFollower() ?: followerPairingStore.isPaired()
        return !isFollower
    }

    open fun isServerRunning(): Boolean {
        return pantrySyncServer.isRunning()
    }

    open fun getServiceState(): StateFlow<HostServiceState> {
        return PantrySyncHostService.serviceState
    }

    open fun resetFailedStateAndRetry(context: Context?): HostStartResult {
        PantrySyncHostService.resetFailedState()
        return ensureHostStarted(context)
    }

    open fun ensureHostStarted(context: Context?): HostStartResult {
        if (!isEligibleToHost()) {
            return HostStartResult.NotEligibleFollower
        }

        synchronized(startLock) {
            val currentState = PantrySyncHostService.serviceState.value
            if (pantrySyncServer.isRunning() || currentState == HostServiceState.STARTING || currentState == HostServiceState.READY) {
                return HostStartResult.AlreadyRunning
            }

            PantrySyncHostService.setStartingForTest()

            if (context == null) {
                return HostStartResult.Failed("Context is null")
            }

            return try {
                val intent = Intent(context, PantrySyncHostService::class.java).apply {
                    action = PantrySyncHostService.ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
                HostStartResult.Success
            } catch (e: Throwable) {
                HostStartResult.Failed(e.message ?: "Failed to start host foreground service")
            }
        }
    }

    open fun stopHost(context: Context) {
        try {
            val intent = Intent(context, PantrySyncHostService::class.java).apply {
                action = PantrySyncHostService.ACTION_STOP
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

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

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .build()
    }

    open suspend fun awaitHttpsHealthReadiness(
        context: Context?,
        hostIp: String,
        port: Int = 8443,
        expectedTlsFingerprint: String,
        maxWaitMs: Long = 5000L,
        pollIntervalMs: Long = 200L
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isEligibleToHost()) return@withContext false
        if (!SyncContractValidation.isValidSha256Fingerprint(expectedTlsFingerprint)) return@withContext false

        ensureHostStarted(context)

        val client = try {
            createPinnedOkHttpClient(expectedTlsFingerprint)
        } catch (e: Exception) {
            rethrowIfCancelled()
            return@withContext false
        }

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (PantrySyncHostService.serviceState.value == HostServiceState.FAILED) {
                return@withContext false
            }

            if (pantrySyncServer.getServerState() == PantryServerState.RUNNING) {
                return@withContext true
            }

            if (pantrySyncServer.getServerState() == PantryServerState.STARTING) {
                val healthUrl = "https://$hostIp:$port/sync/v1/health"
                val isHealthy = try {
                    val request = Request.Builder().url(healthUrl).get().build()
                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: ""
                    val dto = runCatching { json.decodeFromString<HealthResponseDto>(body) }.getOrNull()
                    response.isSuccessful && dto != null && dto.status == "ok" && dto.service == "pantry-household-sync"
                } catch (e: Exception) {
                    rethrowIfCancelled()
                    false
                }

                if (isHealthy) {
                    pantrySyncServer.markRunning()
                    return@withContext true
                }
            }

            delay(pollIntervalMs)
        }

        pantrySyncServer.markFailed()
        false
    }
}
