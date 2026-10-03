package com.example.chatgpt_mealplanner.core.ai.config

data class BackendConfig(
    val baseUrl: String = "http://10.0.2.2:8080", // Android Emulator host fallback
    val devAuthToken: String = "dev-secret-key-pantry-planner",
    val systemPromptVersion: String = "meal-planner-system-v1",
    val provider: String = "openai",
    val useMockProviderInDebug: Boolean = true
)
