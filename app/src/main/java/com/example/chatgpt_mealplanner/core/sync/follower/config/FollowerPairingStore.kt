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
    val keyAlias: String = "pantry_follower_key"
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
            println("[PAIRING_STORE] EncryptedSharedPreferences unavailable, failing closed: ${e.message}")
            null
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

        return FollowerPairingConfig(
            hostIp = hostIp,
            port = port,
            pinnedCertFingerprint = pinnedCertFingerprint,
            deviceId = deviceId,
            sessionToken = sessionToken,
            keyAlias = keyAlias
        )
    }

    open fun savePairingConfig(config: FollowerPairingConfig) {
        val prefs = getPrefs() ?: return
        prefs.edit()
            .putString("KEY_HOST_IP", config.hostIp)
            .putInt("KEY_PORT", config.port)
            .putString("KEY_PINNED_CERT_FINGERPRINT", config.pinnedCertFingerprint)
            .putString("KEY_DEVICE_ID", config.deviceId)
            .putString("KEY_SESSION_TOKEN", config.sessionToken)
            .putString("KEY_KEY_ALIAS", config.keyAlias)
            .apply()
    }

    open fun updateSessionToken(sessionToken: String?) {
        val prefs = getPrefs() ?: return
        prefs.edit().putString("KEY_SESSION_TOKEN", sessionToken).apply()
    }

    open fun clear() {
        val prefs = getPrefs() ?: return
        prefs.edit().clear().apply()
    }
}
