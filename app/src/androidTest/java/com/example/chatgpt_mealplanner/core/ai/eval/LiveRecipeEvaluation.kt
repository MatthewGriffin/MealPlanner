package com.example.chatgpt_mealplanner.core.ai.eval

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore
import com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore
import com.example.chatgpt_mealplanner.core.ai.provider.CLAUDE_MODEL_ID
import com.example.chatgpt_mealplanner.core.ai.provider.ClaudeMealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.provider.TokenUsage
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validation.RecipeOfferChecks
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.entity.KitInstructionEntity
import com.example.chatgpt_mealplanner.core.database.entity.normalizeKitName
import com.example.chatgpt_mealplanner.core.database.RoomTransactionRunner
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.repository.ChatRepositoryImpl
import com.example.chatgpt_mealplanner.core.repository.CookingPreferencesStore
import com.example.chatgpt_mealplanner.core.security.ClaudeCredentialStore
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.MealDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainServiceImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sends real recipe requests to Claude using the key saved in the app, which is charged to that
 * key's Anthropic account. It only runs when asked:
 *
 *   am instrument -w -e class ...eval.LiveRecipeEvaluation -e live true ...
 *
 * Add `-e scenarios allergy-milk,vegan` to run only some, and `-e model claude-sonnet-5-5` to test
 * another model (Haiku 5.5 by default). Everything uses a separate in-memory database, so the app's
 * own data isn't touched. The report goes to the app's files directory as
 * `recipe-evaluation-<model>.txt`, with the tokens used and an estimated cost.
 *
 * The SDK already retries a busy or rate-limited request twice. If it still fails, the request is
 * sent again after an exponential backoff. A run stops when the account is out of credit.
 */
@RunWith(AndroidJUnit4::class)
class LiveRecipeEvaluation {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private enum class Expected { CARD, MEAL_PLAN, TEXT, ANY }

    private class Scenario(
        val id: String,
        val turns: List<String>,
        val preferences: UserPreferencesSummaryDto = UserPreferencesSummaryDto(),
        val stock: List<Stock> = STANDARD_STOCK,
        val expected: Expected = Expected.CARD,
        /** Packet instructions already on file, as if read from a photo earlier. */
        val savedKits: Map<String, String> = emptyMap(),
        /** Words the finished recipe must contain, to show it followed the packet. */
        val mustMention: List<String> = emptyList(),
        /** Kits that have no instructions on file: the model must look them up or ask, never invent a method. */
        val unknownKit: String? = null,
        /** The user has told the assistant there is no packet, so a web search is expected. */
        val searchAllowed: Boolean = false,
        /** A packet photo sent with the last turn: drawn from these lines, with the phone's own text reading assumed to have failed. */
        val packetPhotoLines: List<String>? = null,
        /** After the last turn the kit's saved instructions must exist and contain these words. */
        val kitSavedWith: Pair<String, List<String>>? = null
    )

    private class ScenarioOutcome(val id: String, val calls: Int, val models: Set<String>, val failures: List<String>, val rateLimited: Boolean, val rateLimitWaits: Int, val usage: List<TokenUsage>)

    private class Stock(val name: String, val quantity: Double, val unit: QuantityUnit, val status: ItemStatus = ItemStatus.AVAILABLE, val category: FoodCategory = FoodCategory.FRIDGE)

    /**
     * Counts calls and records what each one returned, because the final result hides the path to it.
     * Waits out per-minute rate limits, so the rest of the app never sees them.
     */
    private class TracingProvider(private val real: MealPlannerAiProvider) : MealPlannerAiProvider by real {
        var calls = 0
        var rateLimitWaits = 0
        val trace = mutableListOf<String>()

        private suspend fun withBackoff(kind: String, call: suspend () -> AiProviderResponse): AiProviderResponse {
            var backoffMs = FIRST_BACKOFF_MS
            var attempt = 0
            while (true) {
                val response = call()
                if (response !is AiProviderResponse.Error || response.code !in BACK_OFF_CODES) return record(kind, response)
                calls++
                if (cantBeWaitedOut(response.message) || attempt == MAX_RATE_LIMIT_RETRIES) {
                    trace += "$kind -> ERROR ${response.code}, giving up: ${response.message.take(600)}"
                    return response
                }
                val waitMs = maxOf(backoffMs, suggestedDelayMs(response.message) ?: 0L) + (0..1000).random()
                trace += "$kind -> limited or busy (${response.code}), waiting ${waitMs / 1000}s before retry ${attempt + 1} of $MAX_RATE_LIMIT_RETRIES"
                rateLimitWaits++
                delay(waitMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                attempt++
            }
        }

        private fun record(kind: String, response: AiProviderResponse): AiProviderResponse {
            calls++
            trace += "$kind -> " + when (response) {
                is AiProviderResponse.ToolCalls -> "tools ${response.calls.map { it.toolName }}" +
                    response.calls.mapNotNull { rawIngredients(it.argsJson) }.joinToString("") { "\n    raw ingredients: $it" } +
                    response.calls.mapNotNull { rawSteps(it.argsJson) }.joinToString("") { "\n    raw steps: $it" }
                is AiProviderResponse.FinalResponse -> "text from ${response.modelId}: ${response.text.take(160).replace('\n', ' ')}"
                is AiProviderResponse.Error -> "ERROR ${response.code}: ${response.message.take(200)}"
            }
            return response
        }

        /** The method as sent, shortened, because an unreadable one is rejected as empty. */
        private fun rawSteps(argsJson: String): String? =
            runCatching { Json.parseToJsonElement(argsJson).jsonObject["steps"]?.toString()?.take(400) ?: "MISSING, keys ${Json.parseToJsonElement(argsJson).jsonObject.keys}" }.getOrNull()
                ?.takeIf { "ingredients" in argsJson }

        /** What the model actually sent, before the app reads units from it. */
        private fun rawIngredients(argsJson: String): String? =
            runCatching { Json.parseToJsonElement(argsJson).jsonObject["ingredients"]?.toString() }.getOrNull()

        override suspend fun send(request: AiProviderRequest): AiProviderResponse =
            withBackoff(if (request.forcedToolName != null) "repair" else "send") { real.send(request) }

        override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
            trace += "tool results: " + request.toolResults.joinToString { result ->
                "${result.toolName}=${if (result.success) "ok" else "REJECTED"} ${(result.errorMessage ?: result.payloadJson.takeIf { "queries" in it }).orEmpty().take(300)}"
            }
            return withBackoff("continue") { real.continueWithToolResults(request) }
        }
    }

    @Test
    fun run() = runBlocking {
        assumeTrue("Costs money on the saved Claude key, so it only runs with -e live true", arguments.getString("live") == "true")
        // The prompt takes the home country from the locale, and the emulator's may not match a real user's.
        java.util.Locale.setDefault(java.util.Locale.UK)
        val wanted = arguments.getString("scenarios")?.split(',')?.map { it.trim() }
        val model = arguments.getString("model")?.trim()?.takeIf { it.isNotEmpty() } ?: CLAUDE_MODEL_ID
        val report = File(context.filesDir, "recipe-evaluation-$model.txt")
        val details = StringBuilder()
        val outcomes = mutableListOf<ScenarioOutcome>()
        for (scenario in SCENARIOS.filter { wanted == null || it.id in wanted }) {
            delay(PAUSE_BETWEEN_SCENARIOS_MS)
            val (text, outcome) = runScenario(scenario, model)
            details.append(text)
            outcomes += outcome
            // Written after each scenario, so a run that stops early still leaves a report.
            report.writeText(summary(model, outcomes) + details)
            if (outcome.rateLimited) {
                details.append("\n\nSTOPPED: still rate limited after backing off, or a daily limit was reached, so the remaining scenarios were not sent.\n")
                break
            }
        }
        report.writeText(summary(model, outcomes) + details)
    }

    private fun summary(model: String, outcomes: List<ScenarioOutcome>): String = buildString {
        val passed = outcomes.count { it.failures.isEmpty() }
        appendLine("MODEL: $model")
        appendLine("SUMMARY: $passed of ${outcomes.size} scenarios had no findings, ${outcomes.sumOf { it.calls }} model requests, ${outcomes.sumOf { it.rateLimitWaits }} rate-limit waits")
        val all = outcomes.flatMap { it.usage }
        appendLine("TOKENS: ${tokens(all)}, ${all.sumOf { it.webSearches }} web searches, estimated cost ${dollars(model, all)} in total, ${dollars(model, all, perScenario = outcomes.size)} a scenario")
        outcomes.forEach { outcome ->
            appendLine("- ${outcome.id}: ${outcome.calls} requests, ${outcome.usage.sumOf { it.webSearches }} searches, ${dollars(model, outcome.usage)}, ${outcome.models}, ${if (outcome.failures.isEmpty()) "clean" else outcome.failures.joinToString(" | ")}")
        }
    }

    private suspend fun runScenario(scenario: Scenario, model: String): Pair<String, ScenarioOutcome> {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            db.chatDao().insertOrUpdateConversation(ChatConversationEntity(CONVERSATION, "Evaluation", now, now))
            db.inventoryDao().insertItems(scenario.stock.mapIndexed { index, item -> item.toEntity(index, now) })
            scenario.savedKits.forEach { (name, instructions) ->
                db.kitInstructionDao().upsert(KitInstructionEntity(normalizeKitName(name), name, instructions, "PHOTO", null, now, now))
            }
            val preferences = CookingPreferencesStore(db.cookingPreferencesDao())
            preferences.save(scenario.preferences)
            val usage = mutableListOf<TokenUsage>()
            val claude = ClaudeMealPlannerAiProvider(ClaudeCredentialStore(context)).apply {
                this.model = model
                usageObserver = { usage += it }
            }
            val provider = TracingProvider(claude)
            val orchestrator = orchestrator(db, provider, preferences)
            val failures = mutableListOf<String>()
            val models = mutableSetOf<String>()
            var rateLimited = false

            val text = buildString {
                appendLine("\n\n===== ${scenario.id} =====")
                appendLine("Preferences: ${scenario.preferences}")
                scenario.turns.forEachIndexed { index, turn ->
                    val before = provider.calls
                    val traceStart = provider.trace.size
                    val started = System.currentTimeMillis()
                    val photo = scenario.packetPhotoLines?.takeIf { index == scenario.turns.lastIndex }?.let { drawnPacket(it) }
                    val result = runCatching { orchestrator.processUserMessage(CONVERSATION, turn, packetPhoto = photo) }
                    appendLine("\n--- USER: $turn")
                    appendLine("[${provider.calls - before} model requests, ${(System.currentTimeMillis() - started) / 1000}s]")
                    provider.trace.drop(traceStart).forEach { appendLine("  trace: $it") }
                    result.fold({ append(describe(it)) }, { appendLine("EXCEPTION: ${it.javaClass.simpleName}") })
                    val outcome = result.getOrNull()
                    (outcome as? AiOrchestrationResult.Final)?.modelId?.let { models += it }
                    rateLimited = rateLimited || (outcome as? AiOrchestrationResult.Error).let { it?.code == "HTTP_429" || it?.userFacingMessage?.contains("credit balance", ignoreCase = true) == true }
                    failures += findings(scenario, outcome, isLastTurn = index == scenario.turns.lastIndex)
                    if (index == scenario.turns.lastIndex) failures += kitFindings(scenario, outcome, db, provider.trace, usage)
                    if (scenario.stock === FLAVOUR_STOCK) {
                        val final = outcome as? AiOrchestrationResult.Final
                        val names = (listOfNotNull(final?.validatedProposal?.proposal) + final?.mealPlan.orEmpty())
                            .flatMap { meal -> meal.ingredients.map { it.displayName.lowercase() } }
                        val used = FLAVOUR_WORDS.filter { word -> names.any { word in it } }
                        appendLine("FLAVOUR USED: ${used.size} of ${FLAVOUR_WORDS.size} $used")
                    }
                }
                kitNotes.forEach { appendLine(it) }
                kitNotes.clear()
                if (failures.isNotEmpty()) appendLine("FINDINGS: $failures")
            }
            return text to ScenarioOutcome(scenario.id, provider.calls, models, failures, rateLimited, provider.rateLimitWaits, usage)
        } finally {
            db.close()
        }
    }

    /** Whether the recipe followed the packet, or the model looked the kit up or asked for it, instead of inventing a method. */
    private suspend fun kitFindings(scenario: Scenario, outcome: AiOrchestrationResult?, db: AppDatabase, trace: List<String>, usage: List<TokenUsage>): List<String> {
        val final = outcome as? AiOrchestrationResult.Final ?: return emptyList()
        val meals = listOfNotNull(final.validatedProposal?.proposal) + final.mealPlan.orEmpty()
        val recipeText = meals.joinToString("\n") { meal -> (meal.ingredients.map { it.displayName } + meal.methodLines()).joinToString(" ") }.lowercase()
        val found = mutableListOf<String>()
        scenario.mustMention.filter { it.lowercase() !in recipeText }.takeIf { it.isNotEmpty() }?.let { found += "recipe doesn't follow the packet: missing $it" }
        scenario.kitSavedWith?.let { (kit, words) ->
            val saved = db.kitInstructionDao().getByName(normalizeKitName(kit))
            val text = saved?.instructions.orEmpty().lowercase()
            when {
                saved == null -> found += "the $kit's instructions were not saved"
                words.any { it.lowercase() !in text } -> found += "saved instructions for the $kit miss ${words.filter { it.lowercase() !in text }}: ${saved.instructions.take(200)}"
            }
            kitNotes += "kit saved=${saved != null} source=${saved?.source} instructions=${saved?.instructions?.take(300)}"
        }
        scenario.unknownKit?.let { kit ->
            val saved = db.kitInstructionDao().getByName(normalizeKitName(kit))
            val searches = usage.sumOf { it.webSearches }
            val askedViaButton = final.kitPacketRequest != null
            when {
                meals.isNotEmpty() && saved == null -> found += "offered a recipe using the $kit without instructions on file (invented method)"
                // After the user has said there is no packet, asking again in words is fine.
                meals.isEmpty() && !askedViaButton && !(scenario.searchAllowed && final.assistantText.lowercase().let { "packet" in it || "instructions" in it }) ->
                    found += "neither used nor asked for the $kit's packet with the Scan packet button"
            }
            // The user usually has the packet, so asking first saves a search that can find the wrong product.
            if (searches > 0 && !scenario.searchAllowed) found += "searched the web ($searches times) before asking the user for the packet"
            appendKitNote(trace, saved != null, searches > 0, askedViaButton)
        }
        if (scenario.searchAllowed && usage.sumOf { it.webSearches } == 0L) found += "was told there is no packet but did not look it up"
        return found
    }

    /** A packet drawn as a picture, as if the phone's text reader had given nothing usable. */
    private fun drawnPacket(lines: List<String>): com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto {
        val bitmap = android.graphics.Bitmap.createBitmap(1200, 900, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 34f }
        lines.forEachIndexed { i, line -> canvas.drawText(line, 30f, 70f + i * 50f, paint) }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto(
            listOf(android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
        )
    }

    private fun appendKitNote(trace: List<String>, saved: Boolean, searched: Boolean, askedViaButton: Boolean) {
        kitNotes += "kit: instructions saved=$saved, web searched=$searched, Scan packet button requested=$askedViaButton, calls=${trace.size}"
    }

    private val kitNotes = mutableListOf<String>()

    private fun findings(scenario: Scenario, outcome: AiOrchestrationResult?, isLastTurn: Boolean): List<String> {
        val final = outcome as? AiOrchestrationResult.Final
            ?: return listOf("failed: ${(outcome as? AiOrchestrationResult.Error)?.code ?: "exception"}")
        val meals = listOfNotNull(final.validatedProposal?.proposal) + final.mealPlan.orEmpty()
        val shape = when {
            !final.mealPlan.isNullOrEmpty() -> Expected.MEAL_PLAN
            final.validatedProposal != null -> Expected.CARD
            else -> Expected.TEXT
        }
        val wrongShape = "expected ${scenario.expected}, got $shape".takeIf {
            isLastTurn && scenario.expected != Expected.ANY && scenario.expected != shape
        }
        val invalid = final.validatedProposal?.issues.orEmpty()
        val checks = meals.flatMap { meal ->
            RecipeOfferChecks.problems(meal, scenario.preferences) +
                RecipeOfferChecks.queries(meal, meal.methodLines().joinToString("\n"), scenario.preferences)
        }
        return listOfNotNull(wrongShape) + invalid + checks
    }

    private fun orchestrator(db: AppDatabase, provider: MealPlannerAiProvider, preferences: CookingPreferencesStore): AiMealPlannerOrchestrator {
        val runner = RoomTransactionRunner(db)
        val pending = PendingMutationStore(db.chatDao())
        val active = ActiveProposalStore(db.chatDao())
        val dispatcher = AiToolDispatcher(
            inventoryDao = db.inventoryDao(),
            mealDao = db.mealDao(),
            leftoverDao = db.leftoverDao(),
            shoppingDao = db.shoppingDao(),
            inventoryDomainService = InventoryDomainServiceImpl(
                inventoryDao = db.inventoryDao(),
                inventoryBarcodeDao = db.inventoryBarcodeDao(),
                transactionDao = db.transactionDao(),
                stapleRuleDao = db.stapleRuleDao(),
                transactionRunner = runner
            ),
            mealDomainService = MealDomainServiceImpl(
                mealDao = db.mealDao(),
                inventoryDao = db.inventoryDao(),
                transactionDao = db.transactionDao(),
                leftoverDao = db.leftoverDao(),
                transactionRunner = runner
            ),
            shoppingDomainService = ShoppingDomainServiceImpl(
                shoppingDao = db.shoppingDao(),
                mealDao = db.mealDao(),
                inventoryDao = db.inventoryDao(),
                stapleRuleDao = db.stapleRuleDao(),
                transactionRunner = runner
            ),
            chatDao = db.chatDao(),
            pendingMutationStore = pending,
            activeProposalStore = active,
            cookingPreferences = preferences,
            kitInstructionDao = db.kitInstructionDao()
        )
        return AiMealPlannerOrchestrator(
            provider = provider,
            contextBuilder = AiContextBuilder(
                inventoryDao = db.inventoryDao(),
                mealDao = db.mealDao(),
                leftoverDao = db.leftoverDao(),
                shoppingDao = db.shoppingDao(),
                activeProposalStore = active,
                pendingMutationStore = pending,
                cookingPreferences = preferences,
                kitInstructionDao = db.kitInstructionDao()
            ),
            toolDispatcher = dispatcher,
            recipeValidator = RecipeValidator(db.inventoryDao(), db.mealDao(), preferences),
            intentResolver = UserMutationIntentResolver(),
            chatRepository = ChatRepositoryImpl(db.chatDao()),
            pendingMutationStore = pending,
            activeProposalStore = active
        )
    }

    private fun describe(result: AiOrchestrationResult): String = buildString {
        when (result) {
            is AiOrchestrationResult.Error -> appendLine("ERROR ${result.code}: ${result.userFacingMessage}")
            is AiOrchestrationResult.Final -> {
                appendLine("MODEL: ${result.modelId}")
                val card = result.validatedProposal
                val plan = result.mealPlan
                when {
                    card != null -> {
                        appendLine("CARD status=${card.status} issues=${card.issues} warnings=${card.warnings}")
                        append(describe(card.proposal))
                    }
                    !plan.isNullOrEmpty() -> {
                        appendLine("MEAL PLAN with ${plan.size} meals")
                        appendLine("Reply: ${result.assistantText}")
                        plan.forEach { append(describe(it)) }
                    }
                    else -> appendLine("TEXT REPLY: ${result.assistantText}")
                }
            }
        }
    }

    private fun describe(meal: MealProposal): String = buildString {
        appendLine("# ${meal.title} (${meal.servings} servings, ${meal.suggestedDate}, ${meal.mealType})")
        appendLine("Time: prep ${meal.prepMinutes}, cook ${meal.cookMinutes}. Equipment: ${meal.equipment}")
        appendLine("Why: ${meal.reasoningSummary}")
        meal.source?.let { appendLine("Based on: $it") }
        meal.worthAdding.forEach { appendLine("Worth adding: $it") }
        meal.ingredients.forEach {
            val extras = listOfNotNull(it.measure, "optional".takeIf { _ -> it.optional }, it.substitute?.let { swap -> "swap: $swap" })
            appendLine("- ${it.displayName}: ${it.quantity} ${it.unit} $extras")
        }
        meal.methodLines().forEach { appendLine(it) }
    }

    private fun Stock.toEntity(index: Int, now: Long) = InventoryItemEntity(
        id = "stock_$index",
        canonicalName = name,
        displayName = name,
        category = category,
        storageLocation = StorageLocation.FRIDGE,
        quantity = quantity,
        quantityUnit = unit,
        quantityConfidence = QuantityConfidence.EXACT,
        status = status,
        frozen = false,
        staple = false,
        createdAt = now,
        updatedAt = now
    )

    private companion object {
        const val CONVERSATION = "default_conversation"
        const val PAUSE_BETWEEN_SCENARIOS_MS = 2_000L
        const val FIRST_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 120_000L
        // 5, 10, 20, 40, 80 and 120 s: over four and a half minutes, so a per-minute limit has long reset.
        const val MAX_RATE_LIMIT_RETRIES = 6
        // 529 is "overloaded". The SDK has already retried these twice before the harness sees them.
        val BACK_OFF_CODES = setOf("HTTP_429", "HTTP_500", "HTTP_503", "HTTP_529", "READ_TIMEOUT")

        /** Running out of credit won't reset by waiting. */
        fun cantBeWaitedOut(message: String) = message.contains("credit balance", ignoreCase = true)

        fun suggestedDelayMs(@Suppress("UNUSED_PARAMETER") message: String): Long? = null

        /** Estimated dollars per million tokens: input, output, cache read, cache write (5-minute). Check before quoting. */
        val PRICES = mapOf(
            "claude-haiku-5-5" to listOf(0.10, 0.50, 0.01, 0.125),
            "claude-sonnet-5-5" to listOf(2.0, 10.0, 0.20, 2.50)
        )

        fun tokens(usage: List<TokenUsage>) =
            "input ${usage.sumOf { it.input }}, output ${usage.sumOf { it.output }}, cache read ${usage.sumOf { it.cacheRead }}, cache write ${usage.sumOf { it.cacheWrite }}"

        fun dollars(model: String, usage: List<TokenUsage>, perScenario: Int = 1): String {
            val price = PRICES[model] ?: return "unknown cost"
            // Web searches are $10 per 1,000 on top of the tokens.
            val total = (usage.sumOf {
                it.input * price[0] + it.output * price[1] + it.cacheRead * price[2] + it.cacheWrite * price[3]
            } / 1_000_000.0 + usage.sumOf { it.webSearches } * 0.01) / perScenario.coerceAtLeast(1)
            return "$" + String.format(java.util.Locale.UK, "%.4f", total)
        }

        val STANDARD_STOCK = listOf(
            Stock("Chicken thighs", 600.0, QuantityUnit.GRAM, ItemStatus.USE_SOON),
            Stock("Tinned chopped tomatoes", 2.0, QuantityUnit.TIN),
            Stock("Onions", 4.0, QuantityUnit.UNIT),
            Stock("Spaghetti", 500.0, QuantityUnit.GRAM),
            Stock("Mature cheddar", 200.0, QuantityUnit.GRAM),
            Stock("Spinach", 150.0, QuantityUnit.GRAM, ItemStatus.USE_SOON),
            Stock("Eggs", 6.0, QuantityUnit.UNIT),
            Stock("Basmati rice", 1.0, QuantityUnit.KILOGRAM),
            Stock("Olive oil", 500.0, QuantityUnit.MILLILITRE),
            Stock("Ground cumin", 40.0, QuantityUnit.GRAM)
        )

        /** Plain staples plus the jars and fresh things that make a dish taste of something. Nothing needs using up. */
        val FLAVOUR_STOCK = listOf(
            Stock("Chicken thighs", 600.0, QuantityUnit.GRAM),
            Stock("Salmon fillets", 2.0, QuantityUnit.UNIT),
            Stock("Spaghetti", 500.0, QuantityUnit.GRAM),
            Stock("Basmati rice", 1.0, QuantityUnit.KILOGRAM),
            Stock("Eggs", 6.0, QuantityUnit.UNIT),
            Stock("Onions", 4.0, QuantityUnit.UNIT),
            Stock("Broccoli", 1.0, QuantityUnit.UNIT),
            Stock("Olive oil", 500.0, QuantityUnit.MILLILITRE),
            Stock("White miso paste", 150.0, QuantityUnit.GRAM),
            Stock("Harissa paste", 90.0, QuantityUnit.GRAM),
            Stock("Gochujang", 200.0, QuantityUnit.GRAM),
            Stock("Soy sauce", 150.0, QuantityUnit.MILLILITRE),
            Stock("Smoked paprika", 40.0, QuantityUnit.GRAM),
            Stock("Parmesan", 150.0, QuantityUnit.GRAM),
            Stock("Feta", 200.0, QuantityUnit.GRAM),
            Stock("Greek yoghurt", 500.0, QuantityUnit.GRAM),
            Stock("Lemons", 3.0, QuantityUnit.UNIT),
            Stock("Fresh coriander", 30.0, QuantityUnit.GRAM),
            Stock("Capers", 100.0, QuantityUnit.GRAM),
            Stock("Honey", 340.0, QuantityUnit.GRAM),
            Stock("Garlic bulb", 1.0, QuantityUnit.UNIT),
            Stock("Fresh ginger", 80.0, QuantityUnit.GRAM),
            Stock("Chilli flakes", 30.0, QuantityUnit.GRAM)
        )

        val FLAVOUR_WORDS = listOf(
            "miso", "harissa", "gochujang", "soy", "smoked paprika", "parmesan", "feta", "yoghurt", "lemon",
            "coriander", "caper", "honey", "garlic", "ginger", "chilli"
        )

        val SCENARIOS = listOf(
            Scenario("pantry-dinner", listOf("suggest a recipe for dinner using what needs using up")),
            Scenario("quick-lunch", listOf("give me a quick recipe for lunch, 20 minutes at most")),
            Scenario("three-day-plan", listOf("create a meal plan with dinners for the next 3 days"), expected = Expected.MEAL_PLAN),
            Scenario("follow-up-spicier", listOf("suggest a recipe for dinner", "make it spicier")),
            Scenario("question-not-recipe", listOf("how long can I keep cooked rice in the fridge?"), expected = Expected.TEXT),
            Scenario("needs-a-choice", listOf("suggest a pizza recipe with toppings of my choice"), expected = Expected.TEXT),
            Scenario(
                "allergy-milk",
                listOf("suggest a creamy pasta recipe for dinner"),
                UserPreferencesSummaryDto(allergies = listOf("Milk"))
            ),
            Scenario(
                "vegan",
                listOf("recommend a hearty recipe for dinner"),
                UserPreferencesSummaryDto(diets = listOf("Vegan"))
            ),
            Scenario(
                "allergies-nut-gluten-dessert",
                listOf("suggest a dessert recipe"),
                UserPreferencesSummaryDto(allergies = listOf("Tree nuts", "Peanuts", "Gluten"))
            ),
            Scenario(
                "beginner-family-air-fryer",
                listOf("give me a chicken recipe for dinner"),
                UserPreferencesSummaryDto(householdSize = 4, skillLevel = "Beginner", equipment = listOf("air fryer"), dislikes = listOf("coriander"))
            ),
            Scenario("breakfast-eggs", listOf("suggest a breakfast recipe with eggs")),
            Scenario("batch-cook", listOf("give me a batch cooking recipe for dinner that freezes well")),
            Scenario("low-carb", listOf("suggest a low carb recipe for dinner")),
            Scenario("cheap", listOf("give me a really cheap dinner recipe")),
            Scenario("soup", listOf("suggest a warming soup recipe for lunch")),
            Scenario("empty-kitchen", listOf("suggest a recipe for dinner"), stock = emptyList()),
            Scenario("only-a-few-items", listOf("what can I cook with just what I have?"), stock = listOf(Stock("Eggs", 4.0, QuantityUnit.UNIT), Stock("Spinach", 100.0, QuantityUnit.GRAM))),
            Scenario("low-stock-not-owned", listOf("suggest a chicken recipe for dinner"), stock = listOf(Stock("Chicken thighs", 50.0, QuantityUnit.GRAM, ItemStatus.LOW))),
            Scenario("question-swap", listOf("what can I use instead of ground cumin?"), expected = Expected.TEXT),
            Scenario(
                "vegetarian-plan",
                listOf("create a meal plan with dinners for the next 3 days"),
                UserPreferencesSummaryDto(diets = listOf("Vegetarian")),
                expected = Expected.MEAL_PLAN
            ),
            Scenario(
                "family-of-five",
                listOf("suggest a pasta recipe for dinner"),
                UserPreferencesSummaryDto(householdSize = 5)
            ),
            Scenario(
                "sesame-soya-stir-fry",
                listOf("suggest a stir fry recipe for dinner"),
                UserPreferencesSummaryDto(allergies = listOf("Sesame", "Soya"))
            ),
            Scenario(
                "coeliac-fish-free",
                listOf("suggest a recipe for dinner"),
                UserPreferencesSummaryDto(allergies = listOf("Gluten", "Fish"))
            ),
            Scenario(
                "halal",
                listOf("suggest a hearty recipe for dinner"),
                UserPreferencesSummaryDto(diets = listOf("Halal"))
            ),
            Scenario(
                "experienced-cook",
                listOf("suggest an impressive recipe for a dinner party"),
                UserPreferencesSummaryDto(skillLevel = "Experienced")
            ),
            // Web recipes: only these ask for one, so only these may search. The first is the same dish without searching.
            Scenario("plain-shawarma", listOf("suggest a chicken shawarma traybake recipe for dinner")),
            Scenario("web-shawarma", listOf("find me a real recipe for chicken shawarma traybake for dinner")),
            Scenario(
                "web-authentic-fish-allergy",
                listOf("give me an authentic Thai green curry recipe for dinner"),
                UserPreferencesSummaryDto(allergies = listOf("Fish"))
            ),
            Scenario("web-named-chef", listOf("look up Jamie Oliver's chicken pie recipe for dinner")),
            // Flavour: a well-stocked kitchen and plain requests. The report counts the owned flavour items each recipe uses.
            Scenario("flavour-dinner", listOf("suggest a recipe for dinner"), stock = FLAVOUR_STOCK),
            Scenario("flavour-lunch", listOf("give me a quick recipe for lunch"), stock = FLAVOUR_STOCK),
            Scenario("flavour-pasta", listOf("suggest a pasta recipe for dinner"), stock = FLAVOUR_STOCK),
            // Kits and mixes: with the packet on file the recipe follows it; without it the model looks it up or asks.
            Scenario(
                "kit-saved-instructions",
                listOf("make dinner for 2 with my fajita kit"),
                stock = listOf(
                    Stock("Old El Paso Fajita Kit", 1.0, QuantityUnit.PACK, category = FoodCategory.KITS_MIXES),
                    Stock("Chicken breast", 400.0, QuantityUnit.GRAM),
                    Stock("Peppers", 3.0, QuantityUnit.UNIT),
                    Stock("Onions", 2.0, QuantityUnit.UNIT),
                    Stock("Vegetable oil", 500.0, QuantityUnit.MILLILITRE)
                ),
                savedKits = mapOf(
                    "Old El Paso Fajita Kit" to "Contains 8 soft tortillas, 1 sachet of fajita seasoning and 1 jar of salsa. Add 400 g chicken strips, 2 peppers and 1 onion, plus 2 tbsp oil. Fry the chicken in the oil for 5 minutes, add the vegetables and cook 3 minutes, stir in the seasoning with 3 tbsp water and cook 2 minutes. Warm the tortillas for 20 seconds in the microwave. Serve with the salsa."
                ),
                mustMention = listOf("seasoning", "water", "tortilla")
            ),
            Scenario(
                "kit-unknown-instructions",
                listOf("use up my Schwartz Slow Cook Chicken Casserole kit for dinner for 2"),
                stock = listOf(
                    Stock("Schwartz Slow Cook Chicken Casserole Seasoning Mix", 1.0, QuantityUnit.PACK, category = FoodCategory.KITS_MIXES),
                    Stock("Chicken thighs", 600.0, QuantityUnit.GRAM),
                    Stock("Potatoes", 600.0, QuantityUnit.GRAM),
                    Stock("Carrots", 4.0, QuantityUnit.UNIT),
                    Stock("Onions", 2.0, QuantityUnit.UNIT)
                ),
                expected = Expected.ANY,
                unknownKit = "Schwartz Slow Cook Chicken Casserole Seasoning Mix"
            ),
            // The user has no packet, so a look-up is the right thing.
            Scenario(
                "kit-no-packet-look-it-up",
                listOf(
                    "use up my Schwartz Slow Cook Chicken Casserole kit for dinner for 2",
                    "I don't have the packet any more, please look it up"
                ),
                stock = listOf(
                    Stock("Schwartz Slow Cook Chicken Casserole Seasoning Mix", 1.0, QuantityUnit.PACK, category = FoodCategory.KITS_MIXES),
                    Stock("Chicken thighs", 600.0, QuantityUnit.GRAM),
                    Stock("Potatoes", 600.0, QuantityUnit.GRAM),
                    Stock("Carrots", 4.0, QuantityUnit.UNIT),
                    Stock("Onions", 2.0, QuantityUnit.UNIT)
                ),
                expected = Expected.ANY,
                unknownKit = "Schwartz Slow Cook Chicken Casserole Seasoning Mix",
                searchAllowed = true
            ),
            // A made-up kit nobody can find online: Claude should ask for the packet, then read the picture sent back.
            Scenario(
                "kit-photo-read-by-claude",
                listOf(
                    "make dinner for 2 with my Cantina Fiesta Sizzle Kit",
                    "here is the back of the packet"
                ),
                stock = listOf(
                    Stock("Cantina Fiesta Sizzle Kit", 1.0, QuantityUnit.PACK, category = FoodCategory.KITS_MIXES),
                    Stock("Pork mince", 500.0, QuantityUnit.GRAM),
                    Stock("Peppers", 2.0, QuantityUnit.UNIT),
                    Stock("Onions", 2.0, QuantityUnit.UNIT)
                ),
                packetPhotoLines = listOf(
                    "CANTINA FIESTA SIZZLE KIT - METHOD",
                    "Kit contains: 1 sachet sizzle spice, 6 corn tacos.",
                    "You will need: 500 g pork mince, 1 pepper, 1 onion,",
                    "150 ml coconut milk.",
                    "1. Brown the mince in a hot pan for 6 minutes.",
                    "2. Add the chopped pepper and onion, cook 4 minutes.",
                    "3. Stir in the sizzle spice and the coconut milk.",
                    "4. Simmer for 7 minutes until thick.",
                    "5. Heat the tacos 1 minute and fill."
                ),
                kitSavedWith = "Cantina Fiesta Sizzle Kit" to listOf("coconut milk", "7 minutes", "mince")
            )
        )
    }
}
