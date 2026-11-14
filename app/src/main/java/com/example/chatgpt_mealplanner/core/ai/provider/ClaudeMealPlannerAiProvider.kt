package com.example.chatgpt_mealplanner.core.ai.provider

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolChoiceNone
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20250305
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
private const val MAX_WEB_SEARCHES = 2L
private const val MAX_PAUSED_TURNS = 3
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
data class TokenUsage(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long, val webSearches: Long = 0)

@Singleton
class ClaudeMealPlannerAiProvider @Inject constructor(
    private val credentialStore: CredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val usageQuota: com.example.chatgpt_mealplanner.core.quota.UsageQuota? = null,
    private val proxyAccount: ProxyAccount? = null
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
        /** The rules, the same for every request, so they can be read from the cache. */
        val rules: String,
        /** This household's context, which changes between requests. */
        val context: String,
        val tools: List<Tool>,
        val messages: List<MessageParam>,
        val pendingToolUseIds: List<String>,
        val webSearch: Boolean = false
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

    /** One request that must end in a call of [toolName]. Returns that call's JSON, or the reason it failed. */
    private suspend fun callSingleTool(
        key: String,
        system: String,
        user: String,
        toolName: String,
        toolDescription: String,
        schema: JsonObject,
        required: List<String>
    ): Pair<String?, String?> {
        val tool = Tool.builder()
            .name(toolName)
            .description(toolDescription)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAllAdditionalProperties(schema.mapValues { (_, value) -> JsonValue.from(plain(value)) })
                            .build()
                    )
                    .required(required)
                    .build()
            )
            .build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(system).build()))
            .addUserMessage(user)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .tools(listOf(ToolUnion.ofTool(tool)))
            .toolChoice(ToolChoiceAuto.builder().build())
            .build()
        return when (val result = call(key, params)) {
            is CallResult.Failed -> null to result.error.message
            is CallResult.Succeeded -> {
                recordUsage(result.message)
                when (result.message.stopReason().orElse(null)) {
                    StopReason.MAX_TOKENS -> return null to "The list was too long to read in one go. Try it in two parts."
                    StopReason.REFUSAL -> return null to BLOCKED_MESSAGE
                    else -> Unit
                }
                val calls = result.message.content().mapNotNull { block -> block.toolUse().orElse(null) }.filter { it.name() == toolName }
                if (calls.isEmpty()) null to "" else calls.joinToString("\u0000") { jsonText(it._input()) } to null
            }
        }
    }

    override suspend fun parsePantryList(text: String): PantryListResult = withContext(ioDispatcher) {
        val key = apiKey() ?: return@withContext PantryListResult.Failed(noKey().message)
        val list = text.trim().take(PantryListParsing.MAX_LIST_CHARS)
        if (list.isEmpty()) return@withContext PantryListResult.Failed("Type or paste what you have first.")
        val (json, error) = callSingleTool(
            key, PantryListParsing.SYSTEM_PROMPT, list, PantryListParsing.TOOL_NAME,
            "Records the household's stock items read from their list.", PantryListParsing.itemsSchema(), listOf("items")
        )
        if (error != null && json == null && error.isNotEmpty()) return@withContext PantryListResult.Failed(error)
        val items = json.orEmpty().split('\u0000').filter { it.isNotBlank() }.flatMap { PantryListParsing.parse(it) }
        if (items.isEmpty()) PantryListResult.Failed("Claude didn't find any food in that list. Try listing items like \"2 kg rice, 3 tins chopped tomatoes\".")
        else PantryListResult.Items(items)
    }

    override suspend fun chooseProducts(lines: List<ProductMatchLine>): ProductMatchResult = withContext(ioDispatcher) {
        val key = apiKey() ?: return@withContext ProductMatchResult.Failed(noKey().message)
        if (lines.isEmpty()) return@withContext ProductMatchResult.Choices(emptyList())
        val (json, error) = callSingleTool(
            key, PantryListParsing.MATCH_PROMPT, PantryListParsing.matchPrompt(lines), PantryListParsing.MATCH_TOOL_NAME,
            "Records which database product each listed line refers to.", PantryListParsing.matchSchema(), listOf("choices")
        )
        if (json == null) return@withContext ProductMatchResult.Failed(error?.ifEmpty { "Claude didn't pick any products." } ?: "Claude didn't pick any products.")
        ProductMatchResult.Choices(json.split('\u0000').filter { it.isNotBlank() }.flatMap { PantryListParsing.parseMatches(it, lines) })
    }

    override suspend fun send(request: AiProviderRequest): AiProviderResponse = withContext(ioDispatcher) {
        val key = apiKey() ?: return@withContext noKey()

        val declaredTools = request.availableTools.filterNot { it in RETIRED_TOOLS }
        val tools = declaredTools.map(::toolDefinition)
        // Forcing a tool isn't allowed on current models, so the instruction goes in the rules instead.
        val forcedTool = request.forcedToolName?.takeIf { name -> declaredTools.any { it.toolName == name } }
        val context = buildString {
            append("Context:\n")
            append(request.contextPayloadJson)
            if (forcedTool != null) {
                append("\n\nAnswer this message by calling the '$forcedTool' tool.")
            }
        }
        val messages = toMessageParams(request.messages)
        conversations.remove(request.conversationId)
        exchange(key, request.conversationId, Conversation(request.systemPrompt, context, tools, messages, emptyList(), request.allowWebSearch), allowToolCalls = true)
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
        start: Conversation,
        allowToolCalls: Boolean
    ): AiProviderResponse {
        var conversation = start
        repeat(MAX_PAUSED_TURNS) {
            val message = when (val result = call(key, params(conversation, allowToolCalls))) {
                is CallResult.Failed -> return result.error
                is CallResult.Succeeded -> result.message
            }
            if (message.stopReason().orElse(null) != StopReason.PAUSE_TURN) return readReply(message, conversationId, conversation)
            // A long web search can pause the turn. Sending it back unchanged lets Claude carry on.
            recordUsage(message)
            conversation = conversation.copy(messages = conversation.messages + message.toParam())
        }
        return AiProviderResponse.Error("REQUEST_TIMEOUT", "Claude took too long searching the web. Please try again.")
    }

    private fun params(conversation: Conversation, allowToolCalls: Boolean): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            // The tools and rules are about 80% of every request and never change, so each ends a cached
            // prefix that any request in the next 5 minutes reads at a tenth of the price. The context
            // after them changes every time. The top-level breakpoint caches the conversation so far.
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder().text(conversation.rules).cacheControl(CacheControlEphemeral.builder().build()).build(),
                    TextBlockParam.builder().text(conversation.context).build()
                )
            )
            .messages(conversation.messages)
            .outputConfig(OutputConfig.builder().effort(effort).build())
            // The rules, context and tools repeat on every tool round, so caching them saves most of the cost.
            .cacheControl(CacheControlEphemeral.builder().build())
            .apply {
                val ownTools = conversation.tools.mapIndexed { index, tool ->
                    if (index == conversation.tools.lastIndex) tool.toBuilder().cacheControl(CacheControlEphemeral.builder().build()).build() else tool
                }
                val all = ownTools.map { ToolUnion.ofTool(it) } +
                    listOfNotNull(webSearchTool().takeIf { conversation.webSearch }?.let { ToolUnion.ofWebSearchTool20250305(it) })
                if (all.isNotEmpty()) {
                    tools(all)
                    if (allowToolCalls) toolChoice(ToolChoiceAuto.builder().build())
                    else toolChoice(ToolChoiceNone.builder().build())
                }
            }
            .build()

    /**
     * Anthropic's own web search, run on their side. The basic version is used because the newer
     * one ran extra filtering code and read a third more tokens for the same answer (2026-11-09).
     */
    private fun webSearchTool(): WebSearchTool20250305 = WebSearchTool20250305.builder()
        .maxUses(MAX_WEB_SEARCHES)
        .apply {
            java.util.Locale.getDefault().country.takeIf { it.length == 2 }?.let { country ->
                userLocation(UserLocation.builder().type(JsonValue.from("approximate")).country(country).build())
            }
        }
        .build()

    private fun recordUsage(message: Message) {
        val usage = message.usage()
        val tokens = TokenUsage(
            input = usage.inputTokens(),
            output = usage.outputTokens(),
            cacheRead = usage.cacheReadInputTokens().orElse(0L),
            cacheWrite = usage.cacheCreationInputTokens().orElse(0L),
            webSearches = usage.serverToolUse().map { it.webSearchRequests() }.orElse(0L)
        )
        usageObserver?.invoke(tokens)
    }

    private fun readReply(message: Message, conversationId: String, conversation: Conversation): AiProviderResponse {
        // A reply that's cut off or refused is still charged.
        recordUsage(message)
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

        val (textWithoutReplies, quickReplies) = ModelReplyText.withoutQuickReplies(text)
        val userFacingText = textWithoutReplies.trim()
        if (userFacingText.isBlank()) {
            return AiProviderResponse.Error("RESPONSE_INVALID", INVALID_MESSAGE)
        }
        return AiProviderResponse.FinalResponse(
            text = userFacingText,
            proposal = ModelReplyText.mealProposal(textWithoutReplies),
            proposedAction = null,
            modelId = model,
            quickReplies = quickReplies
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
                CallResult.Succeeded(runInterruptible {
                    // The Worker adds the allowance to every reply's headers; the raw response lets the app read them.
                    val raw = client(key).messages().withRawResponse().create(params)
                    try {
                        usageQuota?.applyServer(
                            raw.headers().values("x-pp-tier").firstOrNull(),
                            raw.headers().values("x-pp-percent-left").firstOrNull()?.toIntOrNull(),
                            raw.headers().values("x-pp-resets-on").firstOrNull()
                        )
                        raw.parse()
                    } finally {
                        raw.close()
                    }
                })
            } catch (e: Exception) {
                rethrowIfCancelled()
                if (e is AnthropicServiceException) {
                    if (e.statusCode() == 401 && proxyAccount?.isToken(key) == true) proxyAccount.forget()
                    usageQuota?.applyServer(
                        e.headers().values("x-pp-tier").firstOrNull(),
                        e.headers().values("x-pp-percent-left").firstOrNull()?.toIntOrNull(),
                        e.headers().values("x-pp-resets-on").firstOrNull()
                    )
                }
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
            if (status == 402) {
                return AiProviderResponse.Error("USAGE_LIMIT", usageQuota?.limitMessage() ?: "You have used your AI allowance.")
            }
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
        // The user's own key goes straight to Claude; otherwise the install's account token goes to the Worker.
        val url = baseUrl ?: proxyAccount?.takeIf { it.isToken(key) }?.baseUrl
        val cacheKey = "$key|$url"
        cachedClient?.let { (cachedKey, client) -> if (cachedKey == cacheKey) return client }
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .apply { url?.let { baseUrl(it) } }
            .maxRetries(maxRetries)
            .timeout(requestTimeout)
            .build()
        cachedClient?.second?.close()
        cachedClient = cacheKey to client
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
        class Turn(val isUser: Boolean, val text: String, val images: List<String>)
        val turns = mutableListOf<Turn>()
        messages.filter { it.content.isNotBlank() }.forEach { message ->
            val isUser = !message.role.equals("assistant", ignoreCase = true) && !message.role.equals("model", ignoreCase = true)
            val last = turns.lastOrNull()
            if (last != null && last.isUser == isUser) {
                turns[turns.lastIndex] = Turn(isUser, "${last.text}\n\n${message.content}", last.images + message.imagesJpegBase64)
            } else {
                turns += Turn(isUser, message.content, message.imagesJpegBase64)
            }
        }
        if (turns.firstOrNull()?.isUser == false) turns.add(0, Turn(true, "(Continuing our conversation.)", emptyList()))
        if (turns.lastOrNull()?.isUser != true) turns += Turn(true, "Please continue.", emptyList())
        return turns.map { turn ->
            when {
                !turn.isUser -> MessageParam.ofAssistant(turn.text)
                turn.images.isEmpty() -> MessageParam.ofUser(turn.text)
                // The pictures go before the words about them, as Claude reads best.
                else -> MessageParam.ofUserOfContentBlockParams(
                    turn.images.map { image ->
                        ContentBlockParam.ofImage(
                            ImageBlockParam.builder()
                                .source(Base64ImageSource.builder().data(image).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build())
                                .build()
                        )
                    } + ContentBlockParam.ofText(TextBlockParam.builder().text(turn.text).build())
                )
            }
        }
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

    /** The user's own Claude key if they saved one, otherwise this install's account on the Worker. */
    private fun apiKey(): String? = try {
        credentialStore.loadKey()?.trim()?.takeIf { it.isNotEmpty() } ?: proxyAccount?.token()
    } catch (_: Exception) {
        null
    }

    private fun noKey() =
        if (proxyAccount != null) AiProviderResponse.Error("NO_CONNECTION", "Couldn't reach the AI service. Check the internet connection and try again.")
        else AiProviderResponse.Error("NO_API_KEY", "No Claude API key saved. Add one in Settings.")

    private fun safeLog(msg: String) {
        if (!com.example.chatgpt_mealplanner.BuildConfig.DEBUG) return
        try {
            Log.d("CLAUDE_TRACE", msg)
        } catch (_: Throwable) {}
    }
}
