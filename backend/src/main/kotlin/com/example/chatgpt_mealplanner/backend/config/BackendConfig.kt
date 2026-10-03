package com.example.chatgpt_mealplanner.backend.config

data class BackendConfig(
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080,
    val openAiApiKey: String = System.getenv("OPENAI_API_KEY") ?: "",
    val openAiModel: String = System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini",
    val openAiReasoningEffort: String? = System.getenv("OPENAI_REASONING_EFFORT"),
    val devAuthToken: String = System.getenv("DEV_AUTH_TOKEN") ?: "dev-secret-key-pantry-planner",
    val aiProvider: String = System.getenv("AI_PROVIDER") ?: "openai",
    val systemPromptVersion: String = System.getenv("AI_SYSTEM_PROMPT_VERSION") ?: "meal-planner-system-v2",
    val contractVersion: String = System.getenv("CONTRACT_VERSION") ?: "ai-contract-v2",
    val maxMessageCount: Int = 30,
    val maxBodyBytes: Long = 1024 * 1024L,
    val asdaAlgoliaApiKey: String = System.getenv("ASDA_ALGOLIA_API_KEY") ?: System.getProperty("asda.algolia.api.key") ?: ""
)
