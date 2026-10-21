package com.example.chatgpt_mealplanner.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface CredentialStore {
    fun hasKey(): Boolean
    fun saveKey(key: String)
    fun loadKey(): String?
    fun deleteKey()
}

@Singleton
open class GeminiCredentialStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : CredentialStore {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "gemini_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun hasKey(): Boolean {
        return loadKey()?.isNotBlank() == true
    }

    override fun saveKey(key: String) {
        val trimmed = key.trim()
        sharedPreferences.edit()
            .putString("KEY_GEMINI_API_KEY", trimmed)
            .apply()
    }

    override fun loadKey(): String? {
        return sharedPreferences.getString("KEY_GEMINI_API_KEY", null)?.takeIf { it.isNotBlank() }
    }

    override fun deleteKey() {
        sharedPreferences.edit()
            .remove("KEY_GEMINI_API_KEY")
            .apply()
    }
}
