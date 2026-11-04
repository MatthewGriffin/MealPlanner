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
    private val sessions = ConcurrentHashMap<String, ActiveSession>()
    private val secureRandom = SecureRandom()

    internal val pendingChallengeCount: Int get() = challenges.size

    /**
     * Challenges can be requested without authentication, so expired ones are purged and the
     * oldest is evicted at the cap. Under a flood a real device may have to request a new challenge.
     */
    private fun purgeAndBoundChallenges(now: Long) {
        challenges.values.removeIf { now > it.expiresAt }
        while (challenges.size >= MAX_PENDING_CHALLENGES) {
            val oldest = challenges.values.minByOrNull { it.expiresAt } ?: break
            challenges.remove(oldest.challengeId)
        }
    }

    open fun createChallenge(householdId: String, deviceId: String): AuthChallengeDto {
        purgeAndBoundChallenges(System.currentTimeMillis())
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
        // Removing the challenge above is what makes its nonce single-use.
        if (System.currentTimeMillis() > challenge.expiresAt) return null

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
        val now = System.currentTimeMillis()
        val expiresAt = now + 3600_000L

        sessions.values.removeIf { now > it.expiresAt }
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

    internal companion object {
        const val MAX_PENDING_CHALLENGES = 256
    }
}
