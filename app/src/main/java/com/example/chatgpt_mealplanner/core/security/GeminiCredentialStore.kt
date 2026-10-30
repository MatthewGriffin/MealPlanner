package com.example.chatgpt_mealplanner.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface CredentialStore {
    fun hasKey(): Boolean
    fun saveKey(key: String)
    fun replaceKey(key: String) = saveKey(key)
    fun loadKey(): String?
    fun deleteKey()
}

@Singleton
open class GeminiCredentialStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : CredentialStore {
    private val securePreferences by lazy {
        AndroidKeystoreSecurePreferences(context, "gemini_secure_prefs", mapOf("KEY_GEMINI_API_KEY" to 'S'))
    }

    override fun hasKey(): Boolean = loadKey()?.isNotBlank() == true

    override fun saveKey(key: String) {
        if (!securePreferences.putString("KEY_GEMINI_API_KEY", key.trim())) {
            throw IllegalStateException("Could not persist Gemini API key securely")
        }
    }

    override fun replaceKey(key: String) {
        securePreferences.replaceString("KEY_GEMINI_API_KEY", key.trim())
    }

    override fun loadKey(): String? =
        securePreferences.getString("KEY_GEMINI_API_KEY")?.takeIf { it.isNotBlank() }

    override fun deleteKey() {
        if (!securePreferences.remove("KEY_GEMINI_API_KEY")) throw IllegalStateException("Could not remove Gemini API key")
    }
}
