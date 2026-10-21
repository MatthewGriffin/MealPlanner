package com.example.chatgpt_mealplanner.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stores the user's Overpass.ch API key separately from the Gemini credential. */
@Singleton
class StoreDirectoryCredentialStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val securePreferences by lazy {
        AndroidKeystoreSecurePreferences(
            context,
            "store_directory_secure_prefs",
            mapOf(KEY_OVERPASS_API_KEY to 'S')
        )
    }

    fun hasKey(): Boolean = loadKey()?.isNotBlank() == true

    fun saveKey(key: String) {
        val normalized = key.trim()
        require(normalized.isNotBlank()) { "Enter an Overpass.ch API key." }
        if (!securePreferences.putString(KEY_OVERPASS_API_KEY, normalized)) {
            throw IllegalStateException("Could not securely save the Overpass.ch API key.")
        }
    }

    fun loadKey(): String? =
        securePreferences.getString(KEY_OVERPASS_API_KEY)?.takeIf { it.isNotBlank() }

    fun deleteKey() {
        if (!securePreferences.remove(KEY_OVERPASS_API_KEY)) {
            throw IllegalStateException("Could not remove the Overpass.ch API key.")
        }
    }

    private companion object {
        const val KEY_OVERPASS_API_KEY = "KEY_OVERPASS_API_KEY"
    }
}
