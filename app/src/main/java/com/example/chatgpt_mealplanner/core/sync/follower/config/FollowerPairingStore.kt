package com.example.chatgpt_mealplanner.core.sync.follower.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

    private fun getPrefs(): android.content.SharedPreferences? = context?.let { ctx ->
        try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                prefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            println("[PAIRING_STORE] EncryptedSharedPreferences unavailable, falling back to standard prefs: ${e.message}")
            try {
                ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            } catch (_: Exception) {
                null
            }
        }
    }

    open fun isPaired(): Boolean {
        val config = getPairingConfig()
        return config != null && config.hostIp.isNotBlank() && config.pinnedCertFingerprint.isNotBlank() && config.deviceId.isNotBlank()
    }

    open fun getPairingConfig(): FollowerPairingConfig? {
        val prefs = getPrefs() ?: return null
        val hostIp = prefs.getString("KEY_HOST_IP", null) ?: return null
        val port = prefs.getInt("KEY_PORT", 8443)
        val pinnedCertFingerprint = prefs.getString("KEY_PINNED_CERT_FINGERPRINT", null) ?: return null
        val deviceId = prefs.getString("KEY_DEVICE_ID", null) ?: return null
        val sessionToken = prefs.getString("KEY_SESSION_TOKEN", null)
        val keyAlias = prefs.getString("KEY_KEY_ALIAS", "pantry_follower_key") ?: "pantry_follower_key"
        val householdId = prefs.getString("KEY_HOUSEHOLD_ID", null)
        val mainDeviceId = prefs.getString("KEY_MAIN_DEVICE_ID", null)

        return FollowerPairingConfig(
            hostIp = hostIp,
            port = port,
            pinnedCertFingerprint = pinnedCertFingerprint,
            deviceId = deviceId,
            sessionToken = sessionToken,
            keyAlias = keyAlias,
            householdId = householdId,
            mainDeviceId = mainDeviceId
        )
    }

    open fun savePairingConfig(config: FollowerPairingConfig): Boolean {
        val prefs = getPrefs() ?: return false
        val success = prefs.edit()
            .putString("KEY_HOST_IP", config.hostIp)
            .putInt("KEY_PORT", config.port)
            .putString("KEY_PINNED_CERT_FINGERPRINT", config.pinnedCertFingerprint)
            .putString("KEY_DEVICE_ID", config.deviceId)
            .putString("KEY_SESSION_TOKEN", config.sessionToken)
            .putString("KEY_KEY_ALIAS", config.keyAlias)
            .putString("KEY_HOUSEHOLD_ID", config.householdId)
            .putString("KEY_MAIN_DEVICE_ID", config.mainDeviceId)
            .remove("KEY_VERIFIED_BOOTSTRAP_V1")
            .commit()

        if (!success) return false

        val saved = getPairingConfig()
        return saved != null &&
                saved.hostIp == config.hostIp &&
                saved.port == config.port &&
                saved.pinnedCertFingerprint == config.pinnedCertFingerprint &&
                saved.deviceId == config.deviceId
    }

    open fun updateSessionToken(sessionToken: String?) {
        val prefs = getPrefs() ?: return
        prefs.edit().putString("KEY_SESSION_TOKEN", sessionToken).apply()
    }

    open fun markRevoked(): Boolean {
        val prefs = getPrefs() ?: return false
        return prefs.edit().putString("KEY_ACCESS_STATUS", "REVOKED").commit()
    }

    open fun isRevoked(): Boolean {
        val prefs = getPrefs() ?: return false
        return prefs.getString("KEY_ACCESS_STATUS", null) == "REVOKED"
    }

    open fun isStoreInaccessible(): Boolean {
        val ctx = context ?: return false
        return getPrefs() == null
    }

    open fun clearRevoked(): Boolean {
        val prefs = getPrefs() ?: return false
        return prefs.edit().remove("KEY_ACCESS_STATUS").commit()
    }

    open fun isBootstrapVerifiedV1(): Boolean {
        val prefs = getPrefs() ?: return false
        return prefs.getBoolean("KEY_VERIFIED_BOOTSTRAP_V1", false)
    }

    open fun markBootstrapVerifiedV1(): Boolean {
        val prefs = getPrefs() ?: return false
        return prefs.edit().putBoolean("KEY_VERIFIED_BOOTSTRAP_V1", true).commit()
    }

    open fun clear() {
        val prefs = getPrefs() ?: return
        prefs.edit().clear().apply()
    }
}
