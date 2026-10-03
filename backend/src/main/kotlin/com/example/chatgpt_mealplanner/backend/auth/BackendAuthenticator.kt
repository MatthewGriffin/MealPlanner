package com.example.chatgpt_mealplanner.backend.auth

import com.example.chatgpt_mealplanner.backend.config.BackendConfig

data class AuthUserSession(
    val userId: String,
    val isDevMode: Boolean
)

class BackendAuthenticator(private val config: BackendConfig) {

    fun authenticate(devHeader: String?, authHeader: String?): AuthUserSession? {
        if (!devHeader.isNullOrBlank() && devHeader == config.devAuthToken) {
            return AuthUserSession(userId = "dev_user_local", isDevMode = true)
        }

        if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
            val token = authHeader.removePrefix("Bearer ").trim()
            if (token == config.devAuthToken) {
                return AuthUserSession(userId = "dev_user_bearer", isDevMode = true)
            }
        }

        return null
    }
}
