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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
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

    private fun extractMealPlanFromText(text: String): List<com.example.chatgpt_mealplanner.contract.MealProposal>? {
        if (!text.contains("MEAL_PLAN_JSON:")) return null
        return try {
            val array = json.parseToJsonElement(text.substringAfter("MEAL_PLAN_JSON:").trim()).jsonArray
            val meals = array.map { element ->
                val obj = element.jsonObject
                com.example.chatgpt_mealplanner.contract.MealProposal(
                    title = obj["title"]?.jsonPrimitive?.content ?: return null,
                    mealType = obj["mealType"]?.jsonPrimitive?.content,
                    suggestedDate = obj["suggestedDate"]?.jsonPrimitive?.content ?: obj["date"]?.jsonPrimitive?.content,
                    servings = obj["servings"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2,
                    ingredients = obj["ingredients"]?.jsonArray?.map { item ->
                        val ingredient = item.jsonObject
                        com.example.chatgpt_mealplanner.contract.ProposedIngredient(
                            displayName = ingredient["displayName"]?.jsonPrimitive?.content ?: return null,
                            quantity = ingredient["quantity"]?.jsonPrimitive?.content?.toDoubleOrNull()
                                ?: ingredient["plannedQuantity"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null,
                            unit = ingredient["unit"]?.jsonPrimitive?.content ?: ingredient["plannedUnit"]?.jsonPrimitive?.content ?: "ITEM",
                            optional = ingredient["optional"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                            substitutionAllowed = ingredient["substitutionAllowed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                        )
                    } ?: emptyList(),
                    steps = obj["steps"]?.jsonArray?.mapIndexed { index, item ->
                        val step = item.jsonObject
                        com.example.chatgpt_mealplanner.contract.RecipeStep(
                            order = step["order"]?.jsonPrimitive?.content?.toIntOrNull() ?: index + 1,
                            instruction = step["instruction"]?.jsonPrimitive?.content ?: ""
                        )
                    } ?: emptyList(),
                    reasoningSummary = obj["reasoningSummary"]?.jsonPrimitive?.content ?: "Part of the proposed meal plan"
                )
            }
            meals.takeIf { it.size > 1 && it.all { meal -> meal.suggestedDate != null && meal.ingredients.isNotEmpty() && meal.steps.isNotEmpty() } }
        } catch (_: Throwable) {
            null
        }
    }

    private fun postToBackend(endpoint: String, requestJson: String): AiProviderResponse {
        val httpRequest = Request.Builder()
            .url("${backendConfig.baseUrl}$endpoint")
            .header("X-App-Dev-Key", backendConfig.devAuthToken)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (e: SocketTimeoutException) {
            return AiProviderResponse.Error(
                code = "REQUEST_TIMEOUT",
                message = "The AI backend did not respond before the request timed out. Wi-Fi can still be connected; the server may be slow or Android may be restricting network activity."
            )
        } catch (e: UnknownHostException) {
            return AiProviderResponse.Error(
                code = "DNS_FAILURE",
                message = "Android could not resolve the AI backend address (DNS lookup failed). This does not necessarily mean Wi-Fi is disconnected."
            )
        } catch (e: SSLHandshakeException) {
            return AiProviderResponse.Error(
                code = "TLS_ERROR",
                message = "A secure TLS connection to the AI backend could not be established. Check device date/time or network filtering."
            )
        } catch (e: ConnectException) {
            return AiProviderResponse.Error(
                code = "CONNECTION_REFUSED",
                message = "The AI backend refused the connection. The service may be unavailable or its address/port may be incorrect."
            )
        } catch (e: IOException) {
            return AiProviderResponse.Error(
                code = "NETWORK_IO_ERROR",
                message = "The AI request was interrupted during network I/O. Wi-Fi may still be connected; retry and check power-saving restrictions if it repeats."
            )
        } catch (e: Exception) {
            return AiProviderResponse.Error(
                code = "REQUEST_FAILED",
                message = "The AI request failed before a response was received. This is not enough to conclude that internet access is unavailable."
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
            is AiBackendResponse.Final -> {
                val parsedPlan = backendResponse.mealPlan ?: extractMealPlanFromText(backendResponse.text)
                AiProviderResponse.FinalResponse(
                    text = if (parsedPlan != null) backendResponse.text.substringBefore("MEAL_PLAN_JSON:").trim() else backendResponse.text,
                    proposal = if (parsedPlan != null) null else backendResponse.proposal,
                    mealPlan = parsedPlan,
                    proposedAction = backendResponse.proposedAction,
                    modelId = backendResponse.modelId
                )
            }
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
