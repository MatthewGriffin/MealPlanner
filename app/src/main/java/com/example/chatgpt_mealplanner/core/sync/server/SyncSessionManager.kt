package com.example.chatgpt_mealplanner.core.sync.server

import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.security.AndroidKeyStoreManager
import com.example.chatgpt_mealplanner.sync.contract.AuthChallengeDto
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class PendingChallenge(
    val challengeId: String,
    val householdId: String,
    val deviceId: String,
    val nonce: String,
    val expiresAt: Long
)

data class ActiveSession(
    val token: String,
    val deviceId: String,
    val expiresAt: Long
)

@Singleton
open class SyncSessionManager @Inject constructor(
    private val keyStoreManager: AndroidKeyStoreManager,
    private val pairedDeviceDao: PairedDeviceDao
) {
    private val challenges = ConcurrentHashMap<String, PendingChallenge>()
    private val usedNonces = ConcurrentHashMap.newKeySet<String>()
    private val sessions = ConcurrentHashMap<String, ActiveSession>()
    private val secureRandom = SecureRandom()

    open fun createChallenge(householdId: String, deviceId: String): AuthChallengeDto {
        val challengeId = UUID.randomUUID().toString()
        val nonceBytes = ByteArray(32)
        secureRandom.nextBytes(nonceBytes)
        val nonceStr = java.util.Base64.getEncoder().encodeToString(nonceBytes)
        val expiresAt = System.currentTimeMillis() + 60_000L // 60 seconds

        val challenge = PendingChallenge(
            challengeId = challengeId,
            householdId = householdId,
            deviceId = deviceId,
            nonce = nonceStr,
            expiresAt = expiresAt
        )
        challenges[challengeId] = challenge

        return AuthChallengeDto(
            challengeId = challengeId,
            householdId = householdId,
            deviceId = deviceId,
            nonce = nonceStr
        )
    }

    open suspend fun verifyChallengeAndCreateSession(
        challengeId: String,
        deviceId: String,
        signatureBase64: String
    ): String? {
        val challenge = challenges.remove(challengeId) ?: return null
        if (challenge.deviceId != deviceId) return null
        if (System.currentTimeMillis() > challenge.expiresAt) return null
        if (!usedNonces.add(challenge.nonce)) return null // Single-use nonce

        val pairedDevice = pairedDeviceDao.getDeviceById(deviceId) ?: return null
        if (pairedDevice.status != "ACTIVE") return null

        val canonicalChallengeBytes = buildCanonicalChallengeBytes(
            householdId = challenge.householdId,
            deviceId = deviceId,
            challengeId = challengeId,
            nonce = challenge.nonce
        )

        val verified = keyStoreManager.verifySignature(
            publicKeyBase64 = pairedDevice.devicePublicKey,
            challengeData = canonicalChallengeBytes,
            signatureBase64 = signatureBase64
        )

        if (!verified) return null

        // Issue short-lived session token (1 hour)
        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)
        val sessionToken = java.util.Base64.getEncoder().encodeToString(tokenBytes)
        val expiresAt = System.currentTimeMillis() + 3600_000L

        sessions[sessionToken] = ActiveSession(
            token = sessionToken,
            deviceId = deviceId,
            expiresAt = expiresAt
        )

        return sessionToken
    }

    open suspend fun validateSessionToken(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val session = sessions[token] ?: return null
        if (System.currentTimeMillis() > session.expiresAt) {
            sessions.remove(token)
            return null
        }

        val device = pairedDeviceDao.getDeviceById(session.deviceId)
        if (device == null || device.status != "ACTIVE") {
            sessions.remove(token)
            return null
        }

        return session.deviceId
    }

    fun buildCanonicalChallengeBytes(
        householdId: String,
        deviceId: String,
        challengeId: String,
        nonce: String
    ): ByteArray {
        val canonicalStr = "pantry-household-sync|1|$householdId|$deviceId|$challengeId|$nonce"
        return canonicalStr.toByteArray(Charsets.UTF_8)
    }
}
