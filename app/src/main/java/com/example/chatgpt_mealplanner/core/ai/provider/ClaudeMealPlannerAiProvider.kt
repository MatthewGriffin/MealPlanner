package com.example.chatgpt_mealplanner.core.ai.provider

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolChoiceNone
import com.anthropic.models.messages.ToolResultBlockParam
import com.example.chatgpt_mealplanner.contract.AiChatMessage
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.contract.AiToolId
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/** The cheapest model is tried first. The live evaluation compares it with Sonnet before this is settled. */
const val CLAUDE_MODEL_ID = "claude-haiku-5-5"
const val CLAUDE_SONNET_MODEL_ID = "claude-sonnet-5-5"

/** Longest a whole request may take, retries included. */
const val GLOBAL_TIMEOUT_CAP_MS = 180_000L

private const val MAX_TOKENS = 16_000L
private const val BLOCKED_MESSAGE = "Claude declined to answer this. Please try rewording your request."
private const val INVALID_MESSAGE = "Claude returned a reply the app couldn't use. Please tap Retry."
private const val KEY_MESSAGE = "Claude didn't accept the API key. Check the key in Settings."

/** Tools the model is never offered. Live retailer lookups were retired. */
private val RETIRED_TOOLS = setOf(
    AiToolId.GET_RETAIL_BASKET_COMPARISON,
    AiToolId.GET_RETAIL_ITEM_OPTIONS,
    AiToolId.SEARCH_RETAIL_PRODUCTS
)

/** Input excludes the cached tokens, which are counted separately. */
data class TokenUsage(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long)

@Singleton
class ClaudeMealPlannerAiProvider @Inject constructor(
    private val credentialStore: CredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MealPlannerAiProvider {

    /** The live evaluation sets this to compare models. */
    internal var model: String = CLAUDE_MODEL_ID

    /** Tests point this at a local server. */
    internal var baseUrl: String? = null
    internal var maxRetries: Int = 2
    internal var requestTimeout: Duration = Duration.ofSeconds(75)

    /**
     * Medium, Haiku's default. High was tried live on 2026-11-09: replies took 31 s instead of 18,
     * and a 3-day plan ran out of room, because thinking and the answer share [MAX_TOKENS].
     */
    internal var effort: OutputConfig.Effort = OutputConfig.Effort.MEDIUM

    /** Token counts of each reply, for the live evaluation's cost estimate. */
    internal var usageObserver: ((TokenUsage) -> Unit)? = null

    /**
     * The Messages API keeps nothing between requests. A tool round resends the rules, tools and
     * the whole exchange so far, including Claude's own turn exactly as it came back.
     */
    private data class Conversation(
        val system: String,
        val tools: List<Tool>,
        val messages: List<MessageParam>,
        val pendingToolUseIds: List<String>
    )

    private val conversations = ConcurrentHashMap<String, Conversation>()
    private var cachedClient: Pair<String, AnthropicClient>? = null

    override suspend fun testConnection(): AiProviderResponse = withContext(ioDispatcher) {
        val key = apiKey() ?: return@withContext noKey()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(64L)
            .addUserMessage("Reply with OK.")
            .build()
        when (val result = call(key, params)) {
            is CallResult.Failed -> result.error
            is CallResult.Succeeded -> AiProviderResponse.FinalResponse(
                text = "Connected to Claude. Model: $model",
                modelId = model
            )
        }
    }

    override suspend fun send(request: AiProviderRequest): AiProviderResponse = withContext(ioDispatcher) {
        val key = apiKey() ?: return@withContext noKey()

        val declaredTools = request.availableTools.filterNot { it in RETIRED_TOOLS }
        val tools = declaredTools.map(::toolDefinition)
        // Forcing a tool isn't allowed on current models, so the instruction goes in the rules instead.
        val forcedTool = request.forcedToolName?.takeIf { name -> declaredTools.any { it.toolName == name } }
        val system = buildString {
            append(request.systemPrompt)
            append("\n\nContext:\n")
            append(request.contextPayloadJson)
            if (forcedTool != null) {
                append("\n\nAnswer this message by calling the '$forcedTool' tool.")
            }
        }
        val messages = toMessageParams(request.messages)
        conversations.remove(request.conversationId)
        exchange(key, request.conversationId, Conversation(system, tools, messages, emptyList()), allowToolCalls = true)
    }

    override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse = withContext(ioDispatcher) {
        val key = apiKey() ?: return@withContext noKey()
        val conversation = conversations[request.conversationId]
            ?: return@withContext AiProviderResponse.Error(
                "CONTINUATION_MISSING",
                "The app lost track of this conversation with Claude. Please send your message again."
            )

        val results = request.toolResults.associateBy { it.toolCallId }
        // Every tool call needs an answer, or Claude rejects the request.
        val resultBlocks = conversation.pendingToolUseIds.map { id ->
            val result = results[id]
            val builder = ToolResultBlockParam.builder().toolUseId(id)
            if (result == null) {
                builder.content("""{"success":false,"errorMessage":"This tool call wasn't run."}""").isError(true)
            } else {
                builder.content(toolResultContent(result.success, result.payloadJson, result.errorCode, result.errorMessage))
                if (!result.success) builder.isError(true)
            }
            ContentBlockParam.ofToolResult(builder.build())
        }
        val messages = conversation.messages + MessageParam.ofUserOfContentBlockParams(resultBlocks)
        exchange(key, request.conversationId, conversation.copy(messages = messages), allowToolCalls = request.allowMoreToolCalls)
    }

    private suspend fun exchange(
        key: String,
        conversationId: String,
        conversation: Conversation,
        allowToolCalls: Boolean
    ): AiProviderResponse {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(conversation.system)
            .messages(conversation.messages)
            .outputConfig(OutputConfig.builder().effort(effort).build())
            // The rules, context and tools repeat on every tool round, so caching them saves most of the cost.
            .cacheControl(CacheControlEphemeral.builder().build())
            .apply {
                if (conversation.tools.isNotEmpty()) {
                    tools(conversation.tools.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
                    if (allowToolCalls) toolChoice(ToolChoiceAuto.builder().build())
                    else toolChoice(ToolChoiceNone.builder().build())
                }
            }
            .build()

        val message = when (val result = call(key, params)) {
            is CallResult.Failed -> return result.error
            is CallResult.Succeeded -> result.message
        }
        return readReply(message, conversationId, conversation)
    }

    private fun readReply(message: Message, conversationId: String, conversation: Conversation): AiProviderResponse {
        // A reply that's cut off or refused is still charged.
        val usage = message.usage()
        usageObserver?.invoke(
            TokenUsage(
                input = usage.inputTokens(),
                output = usage.outputTokens(),
                cacheRead = usage.cacheReadInputTokens().orElse(0L),
                cacheWrite = usage.cacheCreationInputTokens().orElse(0L)
            )
        )
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return AiProviderResponse.Error("RESPONSE_BLOCKED", BLOCKED_MESSAGE)
            StopReason.MAX_TOKENS -> return AiProviderResponse.Error(
                "RESPONSE_TRUNCATED",
                "Claude's reply was cut off before it finished, so it wasn't used. Please tap Retry."
            )
            StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> return AiProviderResponse.Error(
                "RESPONSE_TRUNCATED",
                "This conversation is too long for Claude. Please start a new chat."
            )
            else -> Unit
        }

        val toolCalls = message.content().mapNotNull { block -> block.toolUse().orElse(null) }.map { use ->
            AiToolCall(toolCallId = use.id(), toolName = use.name(), argsJson = jsonText(use._input()))
        }
        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        safeLog("[CLAUDE] model=${message.model().asString()} stop=${message.stopReason().orElse(null)} toolCalls=${toolCalls.size} textLength=${text.length} cacheRead=${message.usage().cacheReadInputTokens().orElse(0L)}")

        if (toolCalls.isNotEmpty()) {
            // Claude's turn goes back unchanged, thinking included, followed by the tool results.
            conversations[conversationId] = conversation.copy(
                messages = conversation.messages + message.toParam(),
                pendingToolUseIds = toolCalls.map { it.toolCallId }
            )
            return AiProviderResponse.ToolCalls(
                providerContinuationToken = conversationId,
                calls = toolCalls,
                modelId = model
            )
        }

        conversations.remove(conversationId)
        if (text.isBlank()) return AiProviderResponse.Error("EMPTY_RESPONSE", "Claude returned an empty reply. Please tap Retry.")

        val mealPlan = ModelReplyText.mealPlan(text)
        val userFacingText = ModelReplyText.withoutMealPlanJson(text, mealPlan != null)
        if (userFacingText.isBlank() && mealPlan == null) {
            return AiProviderResponse.Error("RESPONSE_INVALID", INVALID_MESSAGE)
        }
        return AiProviderResponse.FinalResponse(
            text = userFacingText,
            proposal = if (mealPlan == null) ModelReplyText.mealProposal(text) else null,
            mealPlan = mealPlan,
            proposedAction = null,
            modelId = model
        )
    }

    private sealed interface CallResult {
        class Succeeded(val message: Message) : CallResult
        class Failed(val error: AiProviderResponse.Error) : CallResult
    }

    private suspend fun call(key: String, params: MessageCreateParams): CallResult {
        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(GLOBAL_TIMEOUT_CAP_MS) {
            try {
                CallResult.Succeeded(runInterruptible { client(key).messages().create(params) })
            } catch (e: Exception) {
                rethrowIfCancelled()
                CallResult.Failed(errorFor(e))
            }
        }
        safeLog("[CLAUDE] elapsedMs=${System.currentTimeMillis() - started} result=${(result as? CallResult.Failed)?.error?.code ?: if (result == null) "TIMEOUT" else "OK"}")
        return result ?: CallResult.Failed(
            AiProviderResponse.Error(
                "REQUEST_TIMEOUT",
                "Claude took longer than ${GLOBAL_TIMEOUT_CAP_MS / 1000} seconds to answer. Please try again."
            )
        )
    }

    private fun errorFor(e: Exception): AiProviderResponse.Error {
        if (e is AnthropicServiceException) {
            val status = e.statusCode()
            val detail = e.message?.take(500).orEmpty()
            val message = when (status) {
                401, 403 -> KEY_MESSAGE
                429 -> "Claude's rate limit was reached. Please wait a minute and try again. ($detail)"
                500, 502, 503, 504, 529 -> "Claude is busy at the moment. Please try again shortly."
                400 -> "Claude rejected the request: $detail"
                else -> "Claude returned an error (HTTP $status): $detail"
            }
            return AiProviderResponse.Error("HTTP_$status", message)
        }
        // OkHttp reports a timeout as an InterruptedIOException, often wrapped by the SDK.
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is InterruptedIOException || it is UnknownHostException || it is SSLException } ?: e
        return when (cause) {
            is InterruptedIOException -> AiProviderResponse.Error(
                "READ_TIMEOUT",
                "Claude didn't answer in time. Please try again."
            )
            is UnknownHostException -> AiProviderResponse.Error(
                "NETWORK_UNAVAILABLE",
                "The phone couldn't find Claude's server. Check the internet connection."
            )
            is SSLException -> AiProviderResponse.Error(
                "TLS_ERROR",
                "A secure connection to Claude couldn't be made. Check the phone's date and time."
            )
            else -> if (e is AnthropicIoException) {
                AiProviderResponse.Error("NETWORK_UNAVAILABLE", "Network connection error. Please check your internet connection.")
            } else {
                AiProviderResponse.Error("PROVIDER_EXCEPTION", "Claude request failed (${e.javaClass.simpleName}).")
            }
        }
    }

    private fun client(key: String): AnthropicClient {
        cachedClient?.let { (cachedKey, client) -> if (cachedKey == key) return client }
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .apply { baseUrl?.let { baseUrl(it) } }
            .maxRetries(maxRetries)
            .timeout(requestTimeout)
            .build()
        cachedClient?.second?.close()
        cachedClient = key to client
        return client
    }

    private fun toolDefinition(tool: AiToolId): Tool {
        val properties = Tool.InputSchema.Properties.builder()
            .putAllAdditionalProperties(ToolSchemas.properties(tool).mapValues { (_, value) -> JsonValue.from(plain(value)) })
            .build()
        return Tool.builder()
            .name(tool.toolName)
            .description(tool.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(properties)
                    .required(ToolSchemas.required(tool))
                    .build()
            )
            .build()
    }

    /**
     * Claude expects alternating turns with the user first and last, and rejects empty text. A
     * recipe card is saved with empty text, and the orchestrator can add two turns in a row.
     */
    internal fun toMessageParams(messages: List<AiChatMessage>): List<MessageParam> {
        val turns = mutableListOf<Pair<Boolean, String>>()
        messages.filter { it.content.isNotBlank() }.forEach { message ->
            val isUser = !message.role.equals("assistant", ignoreCase = true) && !message.role.equals("model", ignoreCase = true)
            val last = turns.lastOrNull()
            if (last != null && last.first == isUser) {
                turns[turns.lastIndex] = isUser to "${last.second}\n\n${message.content}"
            } else {
                turns += isUser to message.content
            }
        }
        if (turns.firstOrNull()?.first == false) turns.add(0, true to "(Continuing our conversation.)")
        if (turns.lastOrNull()?.first != true) turns += true to "Please continue."
        return turns.map { (isUser, text) -> if (isUser) MessageParam.ofUser(text) else MessageParam.ofAssistant(text) }
    }

    private fun toolResultContent(success: Boolean, payloadJson: String, errorCode: String?, errorMessage: String?): String =
        buildString {
            append("{\"success\":").append(success)
            append(",\"content\":").append(Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(payloadJson)))
            errorCode?.let { append(",\"errorCode\":").append(Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(it))) }
            errorMessage?.let { append(",\"errorMessage\":").append(Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(it))) }
            append("}")
        }

    /** The SDK's JSON value, as the JSON text the tool dispatcher reads. */
    private fun jsonText(value: JsonValue): String =
        com.anthropic.core.jsonMapper().writeValueAsString(value)

    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { (_, value) -> plain(value) }
        is JsonArray -> element.map(::plain)
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull
        }
    }

    private fun apiKey(): String? = try {
        credentialStore.loadKey()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun noKey() = AiProviderResponse.Error("NO_API_KEY", "No Claude API key saved. Add one in Settings.")

    private fun safeLog(msg: String) {
        if (!com.example.chatgpt_mealplanner.BuildConfig.DEBUG) return
        try {
            Log.d("CLAUDE_TRACE", msg)
        } catch (_: Throwable) {}
    }
}
