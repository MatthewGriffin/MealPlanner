package com.example.chatgpt_mealplanner.core.sync.enrollment

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class FollowerIdentityStore @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    private val prefsName = "pantry_follower_identity_prefs"

    open fun getOrCreateDeviceId(): String? {
        val ctx = context ?: return null
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                ctx,
                prefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            var devId = prefs.getString("KEY_FOLLOWER_DEVICE_ID", null)
            if (devId.isNullOrBlank()) {
                devId = UUID.randomUUID().toString()
                val saved = prefs.edit().putString("KEY_FOLLOWER_DEVICE_ID", devId).commit()
                if (!saved) return null
            }
            val verifiedId = prefs.getString("KEY_FOLLOWER_DEVICE_ID", null)
            if (verifiedId != devId) null else devId
        } catch (e: Exception) {
            println("[IDENTITY_STORE] Encrypted identity store unavailable, failing closed: ${e.message}")
            null
        }
    }
}
