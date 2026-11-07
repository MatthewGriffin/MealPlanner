package com.example.chatgpt_mealplanner.contract

import kotlinx.serialization.Serializable

@Serializable
data class AiToolCall(
    val toolCallId: String,
    val toolName: String,
    val argsJson: String
)

@Serializable
data class AiToolResult(
    val toolCallId: String,
    val toolName: String,
    val success: Boolean,
    val payloadJson: String,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class AiChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class AiBackendRequest(
    val requestId: String,
    val applicationConversationId: String,
    val contractVersion: String = AI_CONTRACT_VERSION,
    val systemPromptVersion: String = "meal-planner-system-v2",
    val context: AiContextPayload,
    val messages: List<AiChatMessage>,
    val availableTools: List<AiToolId>
)

@Serializable
data class AiBackendContinueRequest(
    val requestId: String,
    val applicationConversationId: String,
    val contractVersion: String = AI_CONTRACT_VERSION,
    val providerContinuationToken: String?,
    val toolResults: List<AiToolResult>
)

@Serializable
sealed interface AiBackendResponse {
    @Serializable
    data class Final(
        val text: String,
        val proposal: MealProposal? = null,
        val mealPlan: List<MealProposal>? = null,
        val proposedAction: AiProposedAction? = null,
        val providerContinuationToken: String? = null,
        val tokenUsage: TokenUsageDto? = null,
        val modelId: String? = null
    ) : AiBackendResponse

    @Serializable
    data class ToolCalls(
        val providerContinuationToken: String?,
        val calls: List<AiToolCall>
    ) : AiBackendResponse

    @Serializable
    data class Error(
        val code: String,
        val userSafeMessage: String,
        val isRetryable: Boolean = false
    ) : AiBackendResponse
}

@Serializable
data class TokenUsageDto(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0
)

data class AiProviderRequest(
    val conversationId: String,
    val systemPrompt: String,
    val contextPayloadJson: String,
    val contextPayload: AiContextPayload,
    val messages: List<AiChatMessage>,
    val availableTools: List<AiToolId>,
    val forcedToolName: String? = null
)

data class AiContinueWithToolsRequest(
    val conversationId: String,
    val providerContinuationToken: String?,
    val toolResults: List<AiToolResult>,
    /** False on the last allowed round, so the model has to answer in text. */
    val allowMoreToolCalls: Boolean = true
)

sealed interface AiProviderResponse {
    data class FinalResponse(
        val text: String,
        val proposal: MealProposal? = null,
        val mealPlan: List<MealProposal>? = null,
        val proposedAction: AiProposedAction? = null,
        val modelId: String? = null
    ) : AiProviderResponse

    data class ToolCalls(
        val providerContinuationToken: String?,
        val calls: List<AiToolCall>,
        val modelId: String? = null
    ) : AiProviderResponse

    data class Error(
        val code: String,
        val message: String
    ) : AiProviderResponse
}

sealed interface AiOrchestrationResult {
    data class Final(
        val assistantText: String,
        val validatedProposal: ValidatedMealProposal? = null,
        val mealPlan: List<MealProposal>? = null,
        val proposedAction: AiProposedAction? = null,
        val modelId: String? = null
    ) : AiOrchestrationResult

    data class Error(
        val code: String,
        val userFacingMessage: String
    ) : AiOrchestrationResult
}
