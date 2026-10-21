package com.example.chatgpt_mealplanner.core.ai.provider

import android.util.Log
import com.example.chatgpt_mealplanner.contract.AiChatMessage
import com.example.chatgpt_mealplanner.contract.AiContextPayload
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiProposedAction
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.contract.AiToolId
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException

const val GEMINI_PRIMARY_MODEL = "gemini-3.7-flash"
const val GEMINI_SECONDARY_MODEL = "gemini-3.6-flash"
const val GEMINI_TERTIARY_MODEL = "gemini-3.5-flash-lite"
const val GEMINI_MODEL_ID = GEMINI_PRIMARY_MODEL
const val GLOBAL_TIMEOUT_CAP_MS = 120_000L

val GEMINI_MODEL_CHAIN = listOf(
    GEMINI_PRIMARY_MODEL,
    GEMINI_SECONDARY_MODEL,
    GEMINI_TERTIARY_MODEL
)

@Singleton
class GeminiMealPlannerAiProvider @Inject constructor(
    private val credentialStore: CredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MealPlannerAiProvider {

    private var apiKey: String? = null

    internal var httpCallHandler: ((urlStr: String, payload: JsonObject) -> Pair<Int, String>)? = null
    internal var delayProvider: (suspend (Long) -> Unit) = { ms ->
        if (httpCallHandler == null) {
            delay(ms)
        }
    }

    constructor(apiKey: String) : this(
        object : CredentialStore {
            override fun hasKey(): Boolean = apiKey.isNotBlank()
            override fun saveKey(key: String) {}
            override fun loadKey(): String = apiKey
            override fun deleteKey() {}
        }
    ) {
        this.apiKey = apiKey
    }

    private val json = Json { ignoreUnknownKeys = true }

    private data class GeminiContinuationState(
        val conversationId: String,
        val historyContents: JsonArray
    )

    private var activeContinuationState: GeminiContinuationState? = null

    override suspend fun testConnection(): AiProviderResponse = withContext(ioDispatcher) {
        runNetworkDiagnostics()

        val activeKey = getEffectiveApiKey()
        if (activeKey.isBlank()) {
            return@withContext AiProviderResponse.Error("NO_API_KEY", "No Gemini API key supplied in BuildConfig or environment.")
        }

        val emptyPayload = AiContextPayload(
            currentDate = "2026-09-28",
            dayOfWeek = "MONDAY",
            preferences = UserPreferencesSummaryDto(),
            recentCookedMeals = emptyList(),
            upcomingPlannedMeals = emptyList(),
            availableLeftovers = emptyList(),
            useSoonItems = emptyList(),
            lowStockStaples = emptyList(),
            activeShoppingList = emptyList()
        )
        val req = AiProviderRequest(
            conversationId = "test_connection",
            systemPrompt = "You are a test assistant.",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = listOf(AiChatMessage(role = "user", content = "Ping")),
            availableTools = emptyList()
        )
        try {
            val res = send(req)
            if (res is AiProviderResponse.FinalResponse) {
                AiProviderResponse.FinalResponse(
                    text = "Connected to Gemini! Model: $GEMINI_MODEL_ID",
                    modelId = res.modelId ?: GEMINI_PRIMARY_MODEL
                )
            } else {
                res
            }
        } catch (e: Exception) {
            AiProviderResponse.Error("CONNECTION_FAILED", e.message ?: "Failed connecting to Gemini API")
        }
    }

    override suspend fun send(request: AiProviderRequest): AiProviderResponse = withContext(ioDispatcher) {
        val activeKey = getEffectiveApiKey()
        if (activeKey.isBlank()) {
            return@withContext AiProviderResponse.Error("NO_API_KEY", "No Gemini API key supplied.")
        }

        val contentsArray = buildJsonArray {
            request.messages.forEach { msg ->
                add(buildJsonObject {
                    put("role", if (msg.role.equals("user", ignoreCase = true)) "user" else "model")
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", msg.content)
                        })
                    }
                })
            }
        }

        val toolsArray = if (request.availableTools.isNotEmpty()) {
            buildJsonArray {
                add(buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        request.availableTools.forEach { tool ->
                            add(buildToolDeclarationJson(tool))
                        }
                    }
                })
            }
        } else null

        val payloadBuilder = { _: String ->
            buildJsonObject {
                put("contents", contentsArray)
                put("systemInstruction", buildJsonObject {
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", "${request.systemPrompt}\n\nContext:\n${request.contextPayloadJson}")
                        })
                    }
                })
                if (toolsArray != null) {
                    put("tools", toolsArray)
                }
                if (!request.forcedToolName.isNullOrBlank()) {
                    val isDeclared = request.availableTools.any { it.toolName == request.forcedToolName }
                    if (isDeclared) {
                        safeLog("[AI_FORCED_TOOL] tool=${request.forcedToolName} reason=STRUCTURED_OFFER_REPAIR")
                        putJsonObject("toolConfig") {
                            putJsonObject("functionCallingConfig") {
                                put("mode", "ANY")
                                putJsonArray("allowedFunctionNames") {
                                    add(request.forcedToolName)
                                }
                            }
                        }
                    } else {
                        safeLog("[AI_FORCED_TOOL] tool=${request.forcedToolName} ERROR=TOOL_NOT_DECLARED")
                    }
                }
            }
        }

        executeGeminiChainWithFallback(activeKey, request.conversationId, payloadBuilder, contentsArray)
    }

    override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse = withContext(ioDispatcher) {
        val activeKey = getEffectiveApiKey()
        if (activeKey.isBlank()) {
            return@withContext AiProviderResponse.Error("NO_API_KEY", "No Gemini API key supplied.")
        }

        val lastHistory = activeContinuationState?.historyContents ?: buildJsonArray {}

        val newHistory = buildJsonArray {
            lastHistory.forEach { add(it) }

            request.toolResults.forEach { res ->
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", res.toolName)
                                putJsonObject("response") {
                                    put("name", res.toolName)
                                    put("content", res.payloadJson)
                                    put("success", res.success)
                                    res.errorCode?.let { put("errorCode", it) }
                                    res.errorMessage?.let { put("errorMessage", it) }
                                }
                            }
                        })
                    }
                })
            }
        }

        val payloadBuilder = { _: String ->
            buildJsonObject {
                put("contents", newHistory)
            }
        }

        executeGeminiChainWithFallback(activeKey, request.conversationId, payloadBuilder, newHistory)
    }

    private suspend fun executeGeminiChainWithFallback(
        activeKey: String,
        conversationId: String,
        payloadBuilder: (modelId: String) -> JsonObject,
        historyContents: JsonArray
    ): AiProviderResponse {
        val result = kotlinx.coroutines.withTimeoutOrNull(GLOBAL_TIMEOUT_CAP_MS) {
            executeGeminiChainLoop(activeKey, conversationId, payloadBuilder, historyContents)
        }

        return result ?: AiProviderResponse.Error(
            "REQUEST_TIMEOUT",
            "Gemini request timed out after ${GLOBAL_TIMEOUT_CAP_MS / 1000}s. Please check network connection and try again."
        )
    }

    private suspend fun executeGeminiChainLoop(
        activeKey: String,
        conversationId: String,
        payloadBuilder: (modelId: String) -> JsonObject,
        historyContents: JsonArray
    ): AiProviderResponse {
        val maxPerModelAttempts = 2
        val maxGlobalAttempts = 4
        var globalAttemptCount = 0

        var lastError: AiProviderResponse.Error? = null

        for (i in GEMINI_MODEL_CHAIN.indices) {
            val currentModel = GEMINI_MODEL_CHAIN[i]
            var modelAttempt = 0

            while (modelAttempt < maxPerModelAttempts && globalAttemptCount < maxGlobalAttempts) {
                modelAttempt++
                globalAttemptCount++

                val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/$currentModel:generateContent?key=$activeKey"
                val payload = payloadBuilder(currentModel)

                val response = executeGeminiHttpCall(urlStr, payload, conversationId, historyContents, currentModel)

                if (response !is AiProviderResponse.Error) {
                    safeLog("[GEMINI_ATTEMPT] model=$currentModel attempt=$globalAttemptCount result=success")
                    safeLog("[GEMINI_MODEL] model=$currentModel result=success")
                    return response
                }

                val errCode = response.code
                safeLog("[GEMINI_ATTEMPT] model=$currentModel attempt=$globalAttemptCount result=$errCode")
                safeLog("[GEMINI_MODEL] model=$currentModel result=$errCode")

                val isQuota = errCode == "HTTP_429" || errCode == "API_QUOTA_EXCEEDED"
                val isCapacity = errCode == "HTTP_503" || errCode == "GEMINI_BUSY"
                val isRetryableTransient = errCode == "HTTP_502" || errCode == "HTTP_504" || errCode == "HTTP_500" || errCode == "READ_TIMEOUT" || errCode == "CONNECT_TIMEOUT"
                val isModelSpecific404 = errCode == "HTTP_404" && (
                    response.message.contains("is not found", ignoreCase = true) ||
                    response.message.contains("not supported for generateContent", ignoreCase = true) ||
                    response.message.contains("NOT_FOUND", ignoreCase = true) ||
                    response.message.contains("models/", ignoreCase = true)
                )

                if (!isQuota && !isCapacity && !isRetryableTransient && !isModelSpecific404) {
                    // Non-quota, non-capacity, non-transient, non-model404 error (400, 401, 403, arbitrary 404) -> Fail immediately, no fallback
                    return response
                }

                lastError = response

                if (isQuota || isModelSpecific404) {
                    // 429 Quota or Model 404 -> proceed immediately to next model in chain without retrying current model
                    break
                }

                if (modelAttempt < maxPerModelAttempts && globalAttemptCount < maxGlobalAttempts) {
                    val baseDelayMs = 1000L
                    val retryAfterMs = extractRetryAfterMs(response.message)
                    val delayMs = (retryAfterMs ?: baseDelayMs).coerceIn(200L, 5000L)
                    safeLog("[GEMINI_RETRY] model=$currentModel attempt=$modelAttempt, status=$errCode, delayMs=$delayMs")
                    delayProvider(delayMs)
                }
            }

            if (i < GEMINI_MODEL_CHAIN.lastIndex && globalAttemptCount < maxGlobalAttempts) {
                val nextModel = GEMINI_MODEL_CHAIN[i + 1]
                val reason = when {
                    lastError?.code == "HTTP_429" || lastError?.code == "API_QUOTA_EXCEEDED" -> "quota"
                    lastError?.code == "HTTP_404" -> "model_unavailable"
                    else -> "capacity"
                }
                safeLog("[GEMINI_FALLBACK] from=$currentModel to=$nextModel reason=$reason")
            }
        }

        return lastError ?: AiProviderResponse.Error("ALL_MODELS_UNAVAILABLE", "Gemini is temporarily unavailable across all models. Please try again shortly.")
    }

    private fun executeGeminiHttpCall(
        urlStr: String,
        payload: JsonObject,
        conversationId: String,
        historyContents: JsonArray,
        currentModel: String
    ): AiProviderResponse {
        val startTime = System.currentTimeMillis()
        val payloadJsonStr = json.encodeToString(payload)
        val bodyBytes = payloadJsonStr.toByteArray(Charsets.UTF_8)

        safeLog("[GEMINI_HTTP] phase=request_start, url=${redactApiKey(urlStr)}, bodyBytes=${bodyBytes.size}, jsonCharCount=${payloadJsonStr.length}")

        val customHandler = httpCallHandler
        if (customHandler != null) {
            return try {
                val (statusCode, responseText) = customHandler(urlStr, payload)
                safeLog("[GEMINI_HTTP] phase=response_code, code=$statusCode, elapsedMs=${System.currentTimeMillis() - startTime}")
                parseGeminiResponse(statusCode, responseText, conversationId, historyContents, startTime, currentModel)
            } catch (e: SocketTimeoutException) {
                safeLog("[GEMINI_HTTP] phase=timeout, exception=${e.javaClass.simpleName}, elapsedMs=${System.currentTimeMillis() - startTime}")
                AiProviderResponse.Error("CONNECT_TIMEOUT", "Connection to Gemini timed out after ${System.currentTimeMillis() - startTime}ms.")
            }
        }

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 15000
            connection.readTimeout = 55_000
            connection.doOutput = true
            connection.doInput = true

            safeLog("[GEMINI_HTTP] phase=connection_created, elapsedMs=${System.currentTimeMillis() - startTime}")

            connection.setFixedLengthStreamingMode(bodyBytes.size)

            connection.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }

            safeLog("[GEMINI_HTTP] phase=request_body_written, bytes=${bodyBytes.size}, elapsedMs=${System.currentTimeMillis() - startTime}")

            val statusCode = try {
                connection.responseCode
            } catch (e: SocketTimeoutException) {
                safeLog("[GEMINI_HTTP] phase=response_code_timeout, exception=SocketTimeoutException, elapsedMs=${System.currentTimeMillis() - startTime}")
                return AiProviderResponse.Error("READ_TIMEOUT", "Gemini connection timed out waiting for server response.")
            } catch (e: UnknownHostException) {
                safeLog("[GEMINI_HTTP] phase=dns_failure, exception=UnknownHostException, elapsedMs=${System.currentTimeMillis() - startTime}")
                return AiProviderResponse.Error("DNS_FAILURE", "DNS lookup failed for Gemini server. Please check internet connection.")
            }

            val retryAfterHeader = connection.getHeaderField("Retry-After")
            val retryAfterInfo = if (!retryAfterHeader.isNullOrBlank()) " [Retry-After: $retryAfterHeader]" else ""

            safeLog("[GEMINI_HTTP] phase=response_code, code=$statusCode$retryAfterInfo, elapsedMs=${System.currentTimeMillis() - startTime}")

            val responseStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            safeLog("[GEMINI_HTTP] phase=response_body_complete, statusCode=$statusCode, bytes=${responseText.length}, elapsedMs=${System.currentTimeMillis() - startTime}")

            parseGeminiResponse(statusCode, responseText, conversationId, historyContents, startTime, currentModel)
        } catch (e: SocketTimeoutException) {
            safeLog("[GEMINI_HTTP] phase=timeout, exception=${e.javaClass.simpleName}, elapsedMs=${System.currentTimeMillis() - startTime}")
            AiProviderResponse.Error("CONNECT_TIMEOUT", "Connection to Gemini API timed out. Please check network connection.")
        } catch (e: UnknownHostException) {
            safeLog("[GEMINI_HTTP] phase=dns_failure, exception=UnknownHostException, elapsedMs=${System.currentTimeMillis() - startTime}")
            AiProviderResponse.Error("NETWORK_UNAVAILABLE", "Network lookup failed for Gemini server. Please check network connection.")
        } catch (e: SSLHandshakeException) {
            safeLog("[GEMINI_HTTP] phase=tls_failure, exception=SSLHandshakeException, elapsedMs=${System.currentTimeMillis() - startTime}")
            AiProviderResponse.Error("TLS_ERROR", "SSL/TLS handshake failed connecting to Gemini API.")
        } catch (e: IOException) {
            safeLog("[GEMINI_HTTP] phase=io_error, exception=${e.javaClass.simpleName}, elapsedMs=${System.currentTimeMillis() - startTime}")
            AiProviderResponse.Error("NETWORK_UNAVAILABLE", "Network connection error. Please check your internet connection.")
        } catch (e: Exception) {
            val exceptionType = e.javaClass.simpleName
            safeLog("[GEMINI_HTTP] phase=exception, exception=$exceptionType, elapsedMs=${System.currentTimeMillis() - startTime}")
            AiProviderResponse.Error("PROVIDER_EXCEPTION", "Gemini provider exception ($exceptionType).")
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    private fun parseGeminiResponse(
        statusCode: Int,
        responseText: String,
        conversationId: String,
        historyContents: JsonArray,
        startTime: Long,
        currentModel: String
    ): AiProviderResponse {
        if (statusCode !in 200..299) {
            val extractedError = extractGeminiErrorMessage(responseText)
            val friendlyMsg = when (statusCode) {
                400 -> "Invalid request format (HTTP 400). Please rephrase your message."
                401, 403 -> "Gemini API key authorization error (HTTP $statusCode). Please check your key in Settings."
                404 -> "The requested Gemini model is unavailable (HTTP 404): $extractedError"
                429 -> "Gemini API rate limit reached (HTTP 429)."
                503 -> "Gemini is temporarily busy (HTTP 503)."
                else -> "Gemini API error (HTTP $statusCode)."
            }
            return AiProviderResponse.Error(
                code = "HTTP_$statusCode",
                message = friendlyMsg
            )
        }

        val responseObj = json.parseToJsonElement(responseText).jsonObject
        val candidates = responseObj["candidates"]?.jsonArray

        if (candidates.isNullOrEmpty()) {
            return AiProviderResponse.Error("EMPTY_RESPONSE", "No response candidates returned from Gemini API.")
        }

        val firstCandidate = candidates.first().jsonObject
        val contentObj = firstCandidate["content"]?.jsonObject
        val partsArray = contentObj?.get("parts")?.jsonArray

        val toolCalls = mutableListOf<AiToolCall>()
        var textResponse = ""

        partsArray?.forEach { partElement ->
            val partObj = partElement.jsonObject
            if (partObj.containsKey("text")) {
                textResponse += partObj["text"]?.jsonPrimitive?.content ?: ""
            }
            if (partObj.containsKey("functionCall")) {
                val fnCall = partObj["functionCall"]?.jsonObject
                val fnName = fnCall?.get("name")?.jsonPrimitive?.content ?: ""
                val fnArgs = fnCall?.get("args")?.jsonObject?.toString() ?: "{}"
                val callIndex = toolCalls.size
                val fnArgsHash = fnArgs.hashCode().toString(16)
                val uniqueToolCallId = "call_${fnName}_${callIndex}_${fnArgsHash}"
                toolCalls.add(
                    AiToolCall(
                        toolCallId = uniqueToolCallId,
                        toolName = fnName,
                        argsJson = fnArgs
                    )
                )
            }
        }

        if (contentObj != null) {
            val nextHistory = buildJsonArray {
                historyContents.forEach { add(it) }
                add(contentObj)
            }
            activeContinuationState = GeminiContinuationState(conversationId, nextHistory)
        }

        safeLog("[GEMINI_HTTP] phase=request_complete, toolCallsCount=${toolCalls.size}, textLength=${textResponse.length}, elapsedMs=${System.currentTimeMillis() - startTime}")

        return if (toolCalls.isNotEmpty()) {
            AiProviderResponse.ToolCalls(
                providerContinuationToken = conversationId,
                calls = toolCalls
            )
        } else if (textResponse.isNotBlank()) {
            val proposal = extractMealProposalFromText(textResponse)
            AiProviderResponse.FinalResponse(
                text = textResponse,
                proposal = proposal,
                proposedAction = null,
                modelId = currentModel
            )
        } else {
            AiProviderResponse.Error("EMPTY_RESPONSE", "Gemini API returned an empty response.")
        }
    }

    private fun extractGeminiErrorMessage(responseText: String): String {
        return try {
            val root = json.parseToJsonElement(responseText).jsonObject
            val errObj = root["error"]?.jsonObject
            errObj?.get("message")?.jsonPrimitive?.content ?: "Model error"
        } catch (_: Exception) {
            "Model error"
        }
    }

    private fun buildToolDeclarationJson(tool: AiToolId): JsonObject {
        return buildJsonObject {
            put("name", tool.toolName)
            put("description", tool.description)
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    when (tool) {
                        AiToolId.ADD_PLANNED_MEAL,
                        AiToolId.OFFER_ADD_PLANNED_MEAL -> {
                            putJsonObject("title") { put("type", "STRING"); put("description", "Meal title (e.g. Shakshuka)") }
                            putJsonObject("date") { put("type", "STRING"); put("description", "Date in YYYY-MM-DD format (e.g. 2026-09-30)") }
                            putJsonObject("mealType") { put("type", "STRING"); put("description", "Meal type: BREAKFAST, LUNCH, DINNER, or SNACK") }
                            putJsonObject("servings") { put("type", "NUMBER"); put("description", "Number of servings (default 2)") }
                            putJsonObject("instructions") { put("type", "STRING"); put("description", "Complete step-by-step cooking instructions for the recipe") }
                            putJsonObject("ingredients") {
                                put("type", "ARRAY")
                                put("description", "List of required ingredients for the meal")
                                put("minItems", 1)
                                putJsonObject("items") {
                                    put("type", "OBJECT")
                                    putJsonObject("properties") {
                                        putJsonObject("displayName") { put("type", "STRING"); put("description", "Ingredient display name") }
                                        putJsonObject("quantity") { put("type", "NUMBER"); put("description", "Quantity needed") }
                                        putJsonObject("unit") { put("type", "STRING"); put("description", "Unit (e.g. GRAM, MILLILITRE, PACK, ITEM)") }
                                        putJsonObject("canonicalIngredientId") { put("type", "STRING"); put("description", "Optional canonical ingredient ID") }
                                    }
                                    putJsonArray("required") {
                                        add("displayName")
                                        add("quantity")
                                        add("unit")
                                    }
                                }
                            }
                        }
                        AiToolId.GET_INVENTORY_FOR_INGREDIENT -> {
                            putJsonObject("canonicalIngredientId") { put("type", "STRING"); put("description", "Canonical ingredient ID (e.g. ING_MILK)") }
                            putJsonObject("searchQuery") { put("type", "STRING"); put("description", "Ingredient search query term") }
                        }
                        AiToolId.GET_MEAL_DETAILS,
                        AiToolId.CANCEL_PLANNED_MEAL,
                        AiToolId.RESTORE_MEAL_TO_PLANNED,
                        AiToolId.PREPARE_MARK_MEAL_COOKED -> {
                            putJsonObject("mealId") { put("type", "STRING"); put("description", "Target meal ID") }
                        }
                        AiToolId.GET_RETAIL_ITEM_OPTIONS -> {
                            putJsonObject("requirementId") { put("type", "STRING"); put("description", "Shopping requirement ID") }
                        }
                        AiToolId.SEARCH_RETAIL_PRODUCTS -> {
                            putJsonObject("query") { put("type", "STRING"); put("description", "Retail product search query") }
                        }
                        AiToolId.MOVE_PLANNED_MEAL -> {
                            putJsonObject("mealId") { put("type", "STRING"); put("description", "Target meal ID") }
                            putJsonObject("newDate") { put("type", "STRING"); put("description", "New date in YYYY-MM-DD format") }
                        }
                        AiToolId.MARK_INVENTORY_LOT_OUT -> {
                            putJsonObject("itemId") { put("type", "STRING"); put("description", "Target inventory lot item ID") }
                        }
                        AiToolId.MARK_ALL_INVENTORY_FOR_CANONICAL_INGREDIENT_OUT -> {
                            putJsonObject("canonicalIngredientId") { put("type", "STRING"); put("description", "Canonical ingredient ID (e.g. ING_MILK)") }
                        }
                        AiToolId.ADJUST_INVENTORY_QUANTITY,
                        AiToolId.RESOLVE_INVENTORY_LOT_UNCERTAINTY -> {
                            putJsonObject("itemId") { put("type", "STRING"); put("description", "Target inventory lot item ID") }
                            putJsonObject("newQuantity") { put("type", "NUMBER"); put("description", "New physical quantity") }
                        }
                        AiToolId.ADD_MANUAL_SHOPPING_ITEM -> {
                            putJsonObject("displayName") { put("type", "STRING"); put("description", "Display name of manual item") }
                            putJsonObject("quantity") { put("type", "NUMBER"); put("description", "Optional required quantity") }
                            putJsonObject("unit") { put("type", "STRING"); put("description", "Optional unit (e.g. GRAM, PACK)") }
                        }
                        AiToolId.SET_STAPLE_STATUS -> {
                            putJsonObject("itemId") { put("type", "STRING"); put("description", "Optional specific inventory item ID") }
                            putJsonObject("searchQuery") { put("type", "STRING"); put("description", "Optional item search query or display name") }
                            putJsonObject("isStaple") { put("type", "BOOLEAN"); put("description", "True to mark as staple, false to remove staple status") }
                        }
                        AiToolId.DELETE_INVENTORY_ITEM -> {
                            putJsonObject("itemId") { put("type", "STRING"); put("description", "Optional specific inventory item ID") }
                            putJsonObject("searchQuery") { put("type", "STRING"); put("description", "Optional item search query") }
                        }
                        AiToolId.DELETE_ALL_INVENTORY -> {
                            putJsonObject("confirmBulkDelete") { put("type", "BOOLEAN"); put("description", "Explicit confirmation flag for bulk delete") }
                        }
                        else -> {
                            // READ tools without arguments keep empty properties object
                        }
                    }
                }
                when (tool) {
                    AiToolId.ADD_PLANNED_MEAL,
                    AiToolId.OFFER_ADD_PLANNED_MEAL -> {
                        putJsonArray("required") {
                            add("title")
                            add("servings")
                            add("ingredients")
                            add("instructions")
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun extractRetryAfterMs(message: String): Long? {
        if (!message.contains("Retry-After:")) return null
        return try {
            val secondsStr = message.substringAfter("Retry-After:").substringBefore("]").trim()
            val seconds = secondsStr.toLongOrNull() ?: return null
            seconds * 1000L
        } catch (_: Throwable) {
            null
        }
    }

    private fun runNetworkDiagnostics() {
        val startTime = System.currentTimeMillis()
        try {
            val addrs = InetAddress.getAllByName("generativelanguage.googleapis.com")
            safeLog("[GEMINI_DIAGNOSTIC] dns_resolved=true, addresses=${addrs.map { it.hostAddress }}, elapsedMs=${System.currentTimeMillis() - startTime}")
        } catch (e: Exception) {
            safeLog("[GEMINI_DIAGNOSTIC] dns_resolved=false, exception=${e.javaClass.simpleName}, message=${e.message}, elapsedMs=${System.currentTimeMillis() - startTime}")
        }

        val httpStartTime = System.currentTimeMillis()
        try {
            val url = URL("https://www.google.com/generate_204")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            conn.disconnect()
            safeLog("[GEMINI_DIAGNOSTIC] google_https_test=SUCCESS, statusCode=$code, elapsedMs=${System.currentTimeMillis() - httpStartTime}")
        } catch (e: Exception) {
            safeLog("[GEMINI_DIAGNOSTIC] google_https_test=FAILED, exception=${e.javaClass.simpleName}, message=${e.message}, elapsedMs=${System.currentTimeMillis() - httpStartTime}")
        }
    }

    private fun getEffectiveApiKey(): String {
        val key = apiKey
        if (!key.isNullOrBlank()) {
            safeLog("[GEMINI_CREDENTIAL] keyRecordExists=true, keyIsBlank=false, keyLength=${key.length}, repositoryKeyPresent=true, providerKeyPresent=true")
            return key
        }
        val storedKey = try { credentialStore.loadKey() } catch (_: Exception) { null }
        if (!storedKey.isNullOrBlank()) {
            safeLog("[GEMINI_CREDENTIAL] keyRecordExists=true, keyIsBlank=false, keyLength=${storedKey.length}, repositoryKeyPresent=true, providerKeyPresent=true")
            return storedKey
        }
        val envKey = System.getenv("GEMINI_API_KEY") ?: ""
        if (envKey.isNotBlank()) {
            safeLog("[GEMINI_CREDENTIAL] keyRecordExists=false, keyIsBlank=false, keyLength=${envKey.length}, repositoryKeyPresent=false, providerKeyPresent=true")
            return envKey
        }
        safeLog("[GEMINI_CREDENTIAL] keyRecordExists=false, keyIsBlank=true, keyLength=0, repositoryKeyPresent=false, providerKeyPresent=false")
        return ""
    }

    private fun extractMealProposalFromText(text: String): MealProposal? {
        if (text.contains("PROPOSAL_JSON:")) {
            return try {
                val jsonPart = text.substringAfter("PROPOSAL_JSON:").substringBefore("\n\n").trim()
                val parsedObj = json.parseToJsonElement(jsonPart).jsonObject
                MealProposal(
                    title = parsedObj["title"]?.jsonPrimitive?.content ?: "Proposed Meal",
                    mealType = parsedObj["mealType"]?.jsonPrimitive?.content,
                    suggestedDate = parsedObj["date"]?.jsonPrimitive?.content,
                    servings = parsedObj["servings"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2,
                    ingredients = parsedObj["ingredients"]?.jsonArray?.map { ingEl ->
                        val ingObj = ingEl.jsonObject
                        ProposedIngredient(
                            canonicalIngredientId = ingObj["canonicalIngredientId"]?.jsonPrimitive?.content,
                            displayName = ingObj["displayName"]?.jsonPrimitive?.content ?: "Ingredient",
                            quantity = ingObj["plannedQuantity"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0,
                            unit = ingObj["plannedUnit"]?.jsonPrimitive?.content ?: "GRAM"
                        )
                    } ?: emptyList(),
                    reasoningSummary = parsedObj["reasoningSummary"]?.jsonPrimitive?.content ?: "AI Generated Meal"
                )
            } catch (_: Throwable) {
                null
            }
        }

        val titleMatch = Regex("\\*\\*([^*]+)\\*\\*").find(text) ?: Regex("###?\\s*([^\\n]+)").find(text)
        val extractedTitle = titleMatch?.groupValues?.get(1)?.trim()
        if (!extractedTitle.isNullOrBlank() && extractedTitle.length in 3..60 && !extractedTitle.equals("Note", ignoreCase = true) && !extractedTitle.equals("Tip", ignoreCase = true)) {
            return MealProposal(
                title = extractedTitle,
                mealType = null,
                servings = 2,
                ingredients = emptyList(),
                reasoningSummary = "Extracted from recipe suggestion"
            )
        }

        return null
    }

    private fun redactApiKey(urlStr: String): String {
        return urlStr.replace(Regex("key=[^&]+"), "key=REDACTED")
    }

    private fun safeLog(msg: String) {
        try {
            Log.d("GEMINI_CREDENTIAL_TRACE", msg)
        } catch (_: Throwable) {}
    }
}
