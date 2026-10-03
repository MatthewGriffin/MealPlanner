package com.example.chatgpt_mealplanner.core.security

class FakeCredentialStore(
    private var storedKey: String? = null
) : CredentialStore {
    override fun hasKey(): Boolean = !storedKey.isNullOrBlank()
    override fun saveKey(key: String) { storedKey = key.trim() }
    override fun loadKey(): String? = storedKey
    override fun deleteKey() { storedKey = null }
}
