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
import com.example.chatgpt_mealplanner.core.ai.provider.GeminiMealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validation.RecipeOfferChecks
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.database.AppDatabase
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
import com.example.chatgpt_mealplanner.core.security.GeminiCredentialStore
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
 * Sends real recipe requests to Gemini using the key saved in the app, which spends that key's
 * quota. It only runs when asked:
 *
 *   am instrument -w -e class ...eval.LiveRecipeEvaluation -e liveGemini true ...
 *
 * Add `-e scenarios allergy-milk,vegan` to run only some. Everything uses a separate in-memory
 * database, so the app's own data isn't touched. The report goes to the app's files directory.
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
        val expected: Expected = Expected.CARD
    )

    private class ScenarioOutcome(val id: String, val calls: Int, val models: Set<String>, val failures: List<String>, val rateLimited: Boolean)

    private class Stock(val name: String, val quantity: Double, val unit: QuantityUnit, val status: ItemStatus = ItemStatus.AVAILABLE)

    /** Counts calls and records what each one returned, because the final result hides the path to it. */
    private class TracingProvider(private val real: MealPlannerAiProvider) : MealPlannerAiProvider by real {
        var calls = 0
        val trace = mutableListOf<String>()

        private fun record(kind: String, response: AiProviderResponse): AiProviderResponse {
            calls++
            trace += "$kind -> " + when (response) {
                is AiProviderResponse.ToolCalls -> "tools ${response.calls.map { it.toolName }}" +
                    response.calls.mapNotNull { rawIngredients(it.argsJson) }.joinToString("") { "\n    raw ingredients: $it" }
                is AiProviderResponse.FinalResponse -> "text from ${response.modelId}: ${response.text.take(160).replace('\n', ' ')}"
                is AiProviderResponse.Error -> "ERROR ${response.code}: ${response.message.take(200)}"
            }
            return response
        }

        /** What the model actually sent, before the app reads units from it. */
        private fun rawIngredients(argsJson: String): String? =
            runCatching { Json.parseToJsonElement(argsJson).jsonObject["ingredients"]?.toString() }.getOrNull()

        override suspend fun send(request: AiProviderRequest): AiProviderResponse =
            record(if (request.forcedToolName != null) "repair" else "send", real.send(request))

        override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
            trace += "tool results: " + request.toolResults.joinToString { result ->
                "${result.toolName}=${if (result.success) "ok" else "REJECTED"} ${(result.errorMessage ?: result.payloadJson.takeIf { "queries" in it }).orEmpty().take(300)}"
            }
            return record("continue", real.continueWithToolResults(request))
        }
    }

    @Test
    fun run() = runBlocking {
        assumeTrue("Spends Gemini quota, so it only runs with -e liveGemini true", arguments.getString("liveGemini") == "true")
        // The prompt takes the home country from the locale, and the emulator's may not match a real user's.
        java.util.Locale.setDefault(java.util.Locale.UK)
        val wanted = arguments.getString("scenarios")?.split(',')?.map { it.trim() }
        val report = File(context.filesDir, "recipe-evaluation.txt")
        val details = StringBuilder()
        val outcomes = mutableListOf<ScenarioOutcome>()
        for (scenario in SCENARIOS.filter { wanted == null || it.id in wanted }) {
            // The free tier limits requests per minute, and hitting that would test the fallback model instead.
            delay(PAUSE_BETWEEN_SCENARIOS_MS)
            var (text, outcome) = runScenario(scenario)
            if (outcome.rateLimited) {
                delay(RATE_LIMIT_WAIT_MS)
                val retry = runScenario(scenario)
                text = retry.first
                outcome = retry.second
            }
            details.append(text)
            outcomes += outcome
            if (outcome.rateLimited) {
                details.append("\n\nSTOPPED: still rate limited after waiting, so the remaining scenarios were not sent.\n")
                break
            }
        }
        report.writeText(summary(outcomes) + details)
    }

    private fun summary(outcomes: List<ScenarioOutcome>): String = buildString {
        val passed = outcomes.count { it.failures.isEmpty() }
        appendLine("SUMMARY: $passed of ${outcomes.size} scenarios had no findings, ${outcomes.sumOf { it.calls }} model requests")
        outcomes.forEach { outcome ->
            appendLine("- ${outcome.id}: ${outcome.calls} requests, ${outcome.models}, ${if (outcome.failures.isEmpty()) "clean" else outcome.failures.joinToString(" | ")}")
        }
    }

    private suspend fun runScenario(scenario: Scenario): Pair<String, ScenarioOutcome> {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            db.chatDao().insertOrUpdateConversation(ChatConversationEntity(CONVERSATION, "Evaluation", now, now))
            db.inventoryDao().insertItems(scenario.stock.mapIndexed { index, item -> item.toEntity(index, now) })
            val preferences = CookingPreferencesStore(db.cookingPreferencesDao())
            preferences.save(scenario.preferences)
            val provider = TracingProvider(GeminiMealPlannerAiProvider(GeminiCredentialStore(context)))
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
                    val result = runCatching { orchestrator.processUserMessage(CONVERSATION, turn) }
                    appendLine("\n--- USER: $turn")
                    appendLine("[${provider.calls - before} model requests, ${(System.currentTimeMillis() - started) / 1000}s]")
                    provider.trace.drop(traceStart).forEach { appendLine("  trace: $it") }
                    result.fold({ append(describe(it)) }, { appendLine("EXCEPTION: ${it.javaClass.simpleName}") })
                    val outcome = result.getOrNull()
                    (outcome as? AiOrchestrationResult.Final)?.modelId?.let { models += it }
                    rateLimited = rateLimited || (outcome as? AiOrchestrationResult.Error)?.code.let { it == "HTTP_429" || it == "API_QUOTA_EXCEEDED" }
                    failures += findings(scenario, outcome, isLastTurn = index == scenario.turns.lastIndex)
                }
                if (failures.isNotEmpty()) appendLine("FINDINGS: $failures")
            }
            return text to ScenarioOutcome(scenario.id, provider.calls, models, failures, rateLimited)
        } finally {
            db.close()
        }
    }

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
            cookingPreferences = preferences
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
                cookingPreferences = preferences
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
        category = FoodCategory.FRIDGE,
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
        const val PAUSE_BETWEEN_SCENARIOS_MS = 15_000L
        const val RATE_LIMIT_WAIT_MS = 65_000L

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
            )
        )
    }
}
