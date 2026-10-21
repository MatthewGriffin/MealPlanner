package com.example.chatgpt_mealplanner.core.sync.enrollment

import android.content.Context
import com.example.chatgpt_mealplanner.core.security.AndroidKeystoreSecurePreferences
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
            val prefs = AndroidKeystoreSecurePreferences(ctx, prefsName, mapOf("KEY_FOLLOWER_DEVICE_ID" to 'S'))
            var id = prefs.getString("KEY_FOLLOWER_DEVICE_ID")
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                if (!prefs.putString("KEY_FOLLOWER_DEVICE_ID", id)) return null
            }
            if (prefs.getString("KEY_FOLLOWER_DEVICE_ID") == id) id else null
        } catch (e: Exception) {
            // Never expose the identity in ordinary preferences when encryption is unavailable.
            println("[IDENTITY_STORE] Secure identity store unavailable: ${e.message}")
            null
        }
    }
}
