package com.example.chatgpt_mealplanner.core.ai.provider

import com.example.chatgpt_mealplanner.contract.AI_CONTRACT_VERSION
import com.example.chatgpt_mealplanner.contract.AiBackendContinueRequest
import com.example.chatgpt_mealplanner.contract.AiBackendRequest
import com.example.chatgpt_mealplanner.contract.AiBackendResponse
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.core.ai.config.BackendConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteMealPlannerAiProvider @Inject constructor(
    private val backendConfig: BackendConfig
) : MealPlannerAiProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override suspend fun send(request: AiProviderRequest): AiProviderResponse {
        val backendRequest = AiBackendRequest(
            requestId = UUID.randomUUID().toString(),
            applicationConversationId = request.conversationId,
            contractVersion = AI_CONTRACT_VERSION,
            systemPromptVersion = backendConfig.systemPromptVersion,
            context = request.contextPayload,
            messages = request.messages,
            availableTools = request.availableTools
        )

        val requestJson = json.encodeToString(backendRequest)
        return postToBackend("/v1/ai/respond", requestJson)
    }

    override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
        val continueRequest = AiBackendContinueRequest(
            requestId = UUID.randomUUID().toString(),
            applicationConversationId = request.conversationId,
            contractVersion = AI_CONTRACT_VERSION,
            providerContinuationToken = request.providerContinuationToken,
            toolResults = request.toolResults
        )

        val requestJson = json.encodeToString(continueRequest)
        return postToBackend("/v1/ai/continue", requestJson)
    }

    private fun postToBackend(endpoint: String, requestJson: String): AiProviderResponse {
        val httpRequest = Request.Builder()
            .url("${backendConfig.baseUrl}$endpoint")
            .header("X-App-Dev-Key", backendConfig.devAuthToken)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (e: Exception) {
            return AiProviderResponse.Error(
                code = "NETWORK_ERROR",
                message = "AI chat needs an internet connection."
            )
        }

        val bodyText = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            return AiProviderResponse.Error(
                code = "HTTP_${response.code}",
                message = "Backend service error (${response.code})."
            )
        }

        val backendResponse = runCatching { json.decodeFromString<AiBackendResponse>(bodyText) }.getOrNull()
            ?: return AiProviderResponse.Error(
                code = "INVALID_BACKEND_RESPONSE",
                message = "Failed to parse backend response."
            )

        return when (backendResponse) {
            is AiBackendResponse.Final -> AiProviderResponse.FinalResponse(
                text = backendResponse.text,
                proposal = backendResponse.proposal,
                proposedAction = backendResponse.proposedAction
            )
            is AiBackendResponse.ToolCalls -> AiProviderResponse.ToolCalls(
                providerContinuationToken = backendResponse.providerContinuationToken,
                calls = backendResponse.calls
            )
            is AiBackendResponse.Error -> AiProviderResponse.Error(
                code = backendResponse.code,
                message = backendResponse.userSafeMessage
            )
        }
    }
}
