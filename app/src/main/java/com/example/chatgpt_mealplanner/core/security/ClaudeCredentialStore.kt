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
open class ClaudeCredentialStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : CredentialStore {
    private val securePreferences by lazy {
        AndroidKeystoreSecurePreferences(context, "claude_secure_prefs", mapOf(KEY to 'S'))
    }

    override fun hasKey(): Boolean = loadKey()?.isNotBlank() == true

    override fun saveKey(key: String) {
        if (!securePreferences.putString(KEY, key.trim())) {
            throw IllegalStateException("Could not persist Claude API key securely")
        }
    }

    override fun replaceKey(key: String) {
        securePreferences.replaceString(KEY, key.trim())
    }

    override fun loadKey(): String? =
        securePreferences.getString(KEY)?.takeIf { it.isNotBlank() }

    override fun deleteKey() {
        if (!securePreferences.remove(KEY)) throw IllegalStateException("Could not remove Claude API key")
    }

    private companion object {
        const val KEY = "KEY_CLAUDE_API_KEY"
    }
}
