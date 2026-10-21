package com.example.chatgpt_mealplanner.core.sync.follower.config

import android.content.Context
import com.example.chatgpt_mealplanner.core.security.AndroidKeystoreSecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class FollowerPairingConfig(
    val hostIp: String,
    val port: Int = 8443,
    val pinnedCertFingerprint: String,
    val deviceId: String,
    val sessionToken: String? = null,
    val keyAlias: String = "pantry_follower_key",
    val householdId: String? = null,
    val mainDeviceId: String? = null
)

@Singleton
open class FollowerPairingStore @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    private val prefsName = "pantry_follower_pairing_prefs"

    private fun getPrefs(): AndroidKeystoreSecurePreferences? = context?.let { ctx ->
        AndroidKeystoreSecurePreferences(
            ctx,
            prefsName,
            mapOf(
                "KEY_HOST_IP" to 'S',
                "KEY_PORT" to 'I',
                "KEY_PINNED_CERT_FINGERPRINT" to 'S',
                "KEY_DEVICE_ID" to 'S',
                "KEY_SESSION_TOKEN" to 'S',
                "KEY_KEY_ALIAS" to 'S',
                "KEY_HOUSEHOLD_ID" to 'S',
                "KEY_MAIN_DEVICE_ID" to 'S',
                "KEY_ACCESS_STATUS" to 'S',
                "KEY_VERIFIED_BOOTSTRAP_V1" to 'B'
            )
        )
    }

    open fun isPaired(): Boolean {
        val config = getPairingConfig()
        return config != null && config.hostIp.isNotBlank() &&
            config.pinnedCertFingerprint.isNotBlank() && config.deviceId.isNotBlank()
    }

    open fun getPairingConfig(): FollowerPairingConfig? {
        val prefs = getPrefs() ?: return null
        val hostIp = prefs.getString("KEY_HOST_IP") ?: return null
        val fingerprint = prefs.getString("KEY_PINNED_CERT_FINGERPRINT") ?: return null
        val deviceId = prefs.getString("KEY_DEVICE_ID") ?: return null
        return FollowerPairingConfig(
            hostIp = hostIp,
            port = prefs.getInt("KEY_PORT", 8443),
            pinnedCertFingerprint = fingerprint,
            deviceId = deviceId,
            sessionToken = prefs.getString("KEY_SESSION_TOKEN"),
            keyAlias = prefs.getString("KEY_KEY_ALIAS", "pantry_follower_key") ?: "pantry_follower_key",
            householdId = prefs.getString("KEY_HOUSEHOLD_ID"),
            mainDeviceId = prefs.getString("KEY_MAIN_DEVICE_ID")
        )
    }

    open fun savePairingConfig(config: FollowerPairingConfig): Boolean {
        val prefs = getPrefs() ?: return false
        val success = prefs.putValues(
            mapOf(
                "KEY_HOST_IP" to config.hostIp,
                "KEY_PORT" to config.port.toString(),
                "KEY_PINNED_CERT_FINGERPRINT" to config.pinnedCertFingerprint,
                "KEY_DEVICE_ID" to config.deviceId,
                "KEY_SESSION_TOKEN" to config.sessionToken,
                "KEY_KEY_ALIAS" to config.keyAlias,
                "KEY_HOUSEHOLD_ID" to config.householdId,
                "KEY_MAIN_DEVICE_ID" to config.mainDeviceId,
                "KEY_VERIFIED_BOOTSTRAP_V1" to null
            )
        )
        if (!success) return false
        val saved = getPairingConfig()
        return saved != null && saved.hostIp == config.hostIp && saved.port == config.port &&
            saved.pinnedCertFingerprint == config.pinnedCertFingerprint && saved.deviceId == config.deviceId
    }

    open fun updateSessionToken(sessionToken: String?) {
        getPrefs()?.putString("KEY_SESSION_TOKEN", sessionToken)
    }

    open fun markRevoked(): Boolean = getPrefs()?.putString("KEY_ACCESS_STATUS", "REVOKED") ?: false
    open fun isRevoked(): Boolean = getPrefs()?.getString("KEY_ACCESS_STATUS") == "REVOKED"

    open fun isStoreInaccessible(): Boolean {
        if (context == null) return true
        return try {
            getPrefs()?.getString("KEY_HOST_IP")
            false
        } catch (_: Exception) {
            true
        }
    }

    open fun clearRevoked(): Boolean = getPrefs()?.remove("KEY_ACCESS_STATUS") ?: false
    open fun isBootstrapVerifiedV1(): Boolean = getPrefs()?.getBoolean("KEY_VERIFIED_BOOTSTRAP_V1", false) ?: false
    open fun markBootstrapVerifiedV1(): Boolean = getPrefs()?.putBoolean("KEY_VERIFIED_BOOTSTRAP_V1", true) ?: false
    open fun clear() { getPrefs()?.clear() }
}
