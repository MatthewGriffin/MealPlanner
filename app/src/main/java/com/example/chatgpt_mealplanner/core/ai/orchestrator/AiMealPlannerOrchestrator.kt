package com.example.chatgpt_mealplanner.core.ai.orchestrator

import com.example.chatgpt_mealplanner.contract.ActionAuthorizationContext
import com.example.chatgpt_mealplanner.contract.AiChatMessage
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.contract.AiToolClass
import com.example.chatgpt_mealplanner.contract.AiToolId
import com.example.chatgpt_mealplanner.contract.AiToolResult
import com.example.chatgpt_mealplanner.contract.ProposalMessagePayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.chatgpt_mealplanner.contract.AuthorizedMutationScope
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import com.example.chatgpt_mealplanner.core.repository.ChatRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiMealPlannerOrchestrator @Inject constructor(
    private val provider: MealPlannerAiProvider,
    private val contextBuilder: AiContextBuilder,
    private val toolDispatcher: AiToolDispatcher,
    private val recipeValidator: RecipeValidator,
    private val intentResolver: UserMutationIntentResolver,
    private val chatRepository: ChatRepository,
    private val pendingMutationStore: com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore,
    private val activeProposalStore: com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore,
    private val appRoleResolver: com.example.chatgpt_mealplanner.core.sync.AppRoleResolver = object : com.example.chatgpt_mealplanner.core.sync.AppRoleResolver(object : com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null) { override fun isPaired() = false }) { override fun isFollower() = false }
) {
    companion object {
        const val MAX_TOOL_ROUNDS = 8
    }

    suspend fun processUserMessage(
        conversationId: String,
        userMessageText: String,
        userMessageId: String? = null,
        displayUserMessageText: String? = null
    ): AiOrchestrationResult {
        if (appRoleResolver.isFollower()) {
            return AiOrchestrationResult.Error(
                code = "FOLLOWER_RESTRICTED",
                userFacingMessage = "AI Assistant features are disabled on Follower devices."
            )
        }
        val pid = try { android.os.Process.myPid() } catch (_: Throwable) { -1 }
        val activeProp = activeProposalStore.getActiveProposal(conversationId)
        val initialPending = pendingMutationStore.getPendingMutation(conversationId)
        val pendingStateStr = when (initialPending) {
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal -> initialPending.state.name
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem -> initialPending.state.name
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteAllInventory -> initialPending.state.name
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.PrepareMarkMealCooked -> initialPending.state.name
            null -> "NONE"
        }

        safeLog("[AI_TURN_START] conversationId=\"$conversationId\" messageLength=${userMessageText.length} processIdentity=$pid activeProposalPresent=${activeProp != null} pendingMutationPresent=${initialPending != null} pendingMutationState=$pendingStateStr")

        val existingMessage = userMessageId?.let { id -> runCatching { chatRepository.getMessagesForConversation(conversationId).firstOrNull { it.id == id } }.getOrNull() }

        val userMsgId = if (existingMessage != null) {
            safeLog("[CHAT_EXPLICIT_RETRY] Reusing existing user message ID=${existingMessage.id}")
            existingMessage.id
        } else {
            val newId = userMessageId ?: "msg_${System.currentTimeMillis()}"
            try {
                chatRepository.saveMessage(
                    ChatMessageEntity(
                        id = newId,
                        conversationId = conversationId,
                        role = "USER",
                        content = displayUserMessageText ?: userMessageText,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Persistence failure does NOT abort execution
            }
            newId
        }

        val isAffirmative = com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClassifier.isAffirmativeConfirmation(userMessageText)
        val isNegative = com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClassifier.isNegativeConfirmation(userMessageText)

        val turnId = "turn_${System.currentTimeMillis()}"
        val traceLog = """
            [DELETE_TRACE] buildId=SYNC_PHASE3D_PULL_FEED_V2 conversationId=$conversationId turnId=$turnId userMessageId=$userMsgId messageLength=${userMessageText.length}
            [DELETE_TRACE] orchestrator_enter conversationId=$conversationId turnId=$turnId
            [DELETE_TRACE] pending_before=${initialPending?.javaClass?.simpleName ?: "NONE"}
            [DELETE_TRACE] affirmative=$isAffirmative
            [DELETE_TRACE] negative=$isNegative
        """.trimIndent()
        safeLog(traceLog)

        if (initialPending != null && isNegative) {
            safeLog("[CONFIRMATION_GATE] messageLength=${userMessageText.length} pendingFound=true pendingType=${initialPending.javaClass.simpleName} negative=true action=DIRECT_CANCEL")
            return executeDirectCancellation(conversationId, userMsgId, initialPending)
        }

        if (initialPending != null && isAffirmative) {
            safeLog("[CONFIRMATION_GATE] messageLength=${userMessageText.length} pendingFound=true pendingType=${initialPending.javaClass.simpleName} affirmative=true action=DIRECT_CONFIRM")
            return executeDirectConfirmation(conversationId, userMsgId, initialPending)
        }

        val intentClass = com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClassifier.classify(
            userMessageText = userMessageText,
            pendingMutation = initialPending,
            hasActiveProposal = activeProp != null
        )

        safeLog("[AI_CONTEXT_SELECTION] messageLength=${userMessageText.length} currentIntent=$intentClass activeProposalPresent=${activeProp != null} pendingMutation=${initialPending?.javaClass?.simpleName}")

        if (intentClass == com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.FOLLOW_UP_TO_ACTIVE_PROPOSAL) {
            val pendingTitle = (initialPending as? com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal)?.title
            safeLog("[FOLLOW_UP_PROPOSAL] detected=true pendingPreserved=true pendingProposalPresent=${pendingTitle != null || activeProp != null} questionLength=${userMessageText.length}")
        } else if (intentClass == com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.CANCEL_PENDING ||
            intentClass == com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.NEW_READ_OR_PROPOSAL_REQUEST) {
            safeLog("[PENDING_MUTATION_STATE] Cleared stale pending mutation due to $intentClass")
            pendingMutationStore.clearPendingMutation(conversationId, intentClass.name)
            activeProposalStore.clearActiveProposal(conversationId)
        }

        val effectivePending = pendingMutationStore.getPendingMutation(conversationId)
        val authContext = intentResolver.resolveAuthorizationContext(userMsgId, userMessageText, effectivePending)

        if (intentClass == com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.CONFIRM_PENDING_MUTATION) {
            val pendingAdd = effectivePending as? com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
            if (pendingAdd != null) {
                safeLog("[CONFIRM_PENDING_MUTATION] found=true title=\"${pendingAdd.title}\" date=\"${pendingAdd.date}\" mealType=\"${pendingAdd.mealType?.name}\" ingredientCount=${pendingAdd.ingredients.size}")
            } else {
                safeLog("[CONFIRM_PENDING_MUTATION] found=false")
            }
        }
        val contextPayload = contextBuilder.buildContextPayload(conversationId)
        val contextPayloadJson = contextBuilder.buildContextPayloadJson(conversationId)

        val historicalMessages = try {
            chatRepository.getMessagesForConversation(conversationId).takeLast(20).mapNotNull { message ->
                // Persist a concise summary for the user, but send the full internal prompt to Gemini.
                if (message.id == userMsgId && message.role.equals("USER", ignoreCase = true)) {
                    AiChatMessage(role = "user", content = userMessageText)
                } else {
                    historyTextFor(message)?.let { AiChatMessage(role = message.role.lowercase(), content = it) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            listOf(AiChatMessage(role = "user", content = userMessageText))
        }

        val historyCount = historicalMessages.size
        val assistantCount = historicalMessages.count { it.role.equals("assistant", ignoreCase = true) || it.role.equals("model", ignoreCase = true) }
        val userCount = historicalMessages.count { it.role.equals("user", ignoreCase = true) }
        val lastAssistant = historicalMessages.lastOrNull { it.role.equals("assistant", ignoreCase = true) || it.role.equals("model", ignoreCase = true) }
        val lastAssistantIncluded = lastAssistant != null
        val lastAssistantChars = lastAssistant?.content?.length ?: 0

        safeLog("[AI_CONTEXT] historyMessageCount=$historyCount assistantMessages=$assistantCount userMessages=$userCount lastAssistantIncluded=$lastAssistantIncluded lastAssistantChars=$lastAssistantChars")

        val request = AiProviderRequest(
            conversationId = conversationId,
            systemPrompt = SYSTEM_PROMPT.replace(
                "{{HOME_COUNTRY}}",
                java.util.Locale.getDefault().displayCountry.ifBlank { "United Kingdom" }
            ),
            contextPayloadJson = contextPayloadJson,
            contextPayload = contextPayload,
            messages = historicalMessages,
            // A meal-plan request is a menu-level workflow, not a single-recipe proposal.
            // Keep recipe-offer tools out so the first meal cannot become an accidental Save meal card.
            availableTools = AiToolId.entries.filter { tool ->
                if (!isMealPlanRequest(userMessageText)) {
                    // Single-recipe requests need this tool so the model can return a
                    // complete, confirmable recipe offer. Meal-plan requests use the
                    // separate menu-level flow below and intentionally exclude it.
                    tool != AiToolId.REGENERATE_SHOPPING_LIST
                } else {
                    // Meal-plan generation should not wait on retailer catalogue/network lookups.
                    // Retailer matching happens later when the user expands a selected meal.
                    tool in setOf(
                        AiToolId.GET_INVENTORY_SUMMARY,
                        AiToolId.GET_INVENTORY_FOR_INGREDIENT,
                        AiToolId.GET_USABLE_INVENTORY,
                        AiToolId.GET_LOW_STOCK_ITEMS,
                        AiToolId.GET_USE_SOON_ITEMS,
                        AiToolId.GET_RECENT_COOKED_MEALS,
                        AiToolId.GET_UPCOMING_PLANNED_MEALS,
                        AiToolId.GET_MEAL_DETAILS,
                        AiToolId.GET_AVAILABLE_LEFTOVERS,
                        AiToolId.GET_ACTIVE_SHOPPING_LIST
                    )
                }
            },
            // Live retailer catalogue scraping has been retired; never force the deprecated lookup tool.
            forcedToolName = null
        )

        val mealPlanRequest = isMealPlanRequest(userMessageText)
        safeLog("[AI_PLAN_MODE] enabled=$mealPlanRequest retailerLookups=DISABLED")
        var providerResponse = provider.send(request)
        var roundCount = 0
        var readSucceeded = false
        var writeCommitted = false
        var plannerMutationCommitted = false
        var shoppingMutationCommitted = false
        var plannerWriteRequestedThisTurn = false
        var requestedWritesCount = 0
        var successfulWritesCount = 0
        var failedWritesCount = 0

        while (providerResponse is AiProviderResponse.ToolCalls) {
            val maxRoundsForRequest = if (mealPlanRequest) 2 else MAX_TOOL_ROUNDS
            if (roundCount >= maxRoundsForRequest) {
                return AiOrchestrationResult.Error(
                    code = "MAX_TOOL_ROUNDS_EXCEEDED",
                    userFacingMessage = "Exceeded maximum tool execution rounds."
                )
            }
            roundCount++

            val toolCalls = (providerResponse as AiProviderResponse.ToolCalls).calls
            val continuationToken = (providerResponse as AiProviderResponse.ToolCalls).providerContinuationToken

            val toolResults = mutableListOf<AiToolResult>()
            for (call in toolCalls) {
                val toolId = AiToolId.fromToolName(call.toolName)
                val executionClass = toolId?.toolClass ?: AiToolClass.READ

                safeLog("[AI_MODEL_TOOL_REQUEST] tool=${call.toolName} toolCallId=${call.toolCallId}")

                val res = toolDispatcher.executeToolCall(conversationId, authContext, call)
                toolResults.add(res)

                val isWrite = executionClass == AiToolClass.WRITE_EXPLICIT || executionClass == AiToolClass.WRITE_CONFIRMATION_REQUIRED
                val isWriteCommitted = isWrite && res.success
                val isReadSuccess = executionClass == AiToolClass.READ && res.success

                if (isWrite) {
                    requestedWritesCount++
                    if (toolId == AiToolId.ADD_PLANNED_MEAL || toolId == AiToolId.MOVE_PLANNED_MEAL || toolId == AiToolId.CANCEL_PLANNED_MEAL || toolId == AiToolId.RESTORE_MEAL_TO_PLANNED) {
                        plannerWriteRequestedThisTurn = true
                    }
                    if (res.success) {
                        successfulWritesCount++
                        writeCommitted = true
                        if (toolId == AiToolId.ADD_PLANNED_MEAL || toolId == AiToolId.MOVE_PLANNED_MEAL || toolId == AiToolId.CANCEL_PLANNED_MEAL || toolId == AiToolId.RESTORE_MEAL_TO_PLANNED) {
                            plannerMutationCommitted = true
                        }
                        if (toolId == AiToolId.REGENERATE_SHOPPING_LIST || toolId == AiToolId.ADD_MANUAL_SHOPPING_ITEM) {
                            shoppingMutationCommitted = true
                        }
                    } else {
                        failedWritesCount++
                    }
                }

                if (isReadSuccess) {
                    readSucceeded = true
                }

                safeLog("[AI_TOOL_EXECUTION] tool=${call.toolName} executionClass=$executionClass authorized=${res.errorCode != "UNAUTHORIZED"} success=${res.success} mutationCommitted=$isWriteCommitted")
            }

            safeLog("[AI_WRITE_SUMMARY] requestedWrites=$requestedWritesCount successfulWrites=$successfulWritesCount failedWrites=$failedWritesCount plannerMutationCommitted=$plannerMutationCommitted shoppingMutationCommitted=$shoppingMutationCommitted")

            val continueRequest = AiContinueWithToolsRequest(
                conversationId = conversationId,
                providerContinuationToken = continuationToken,
                toolResults = toolResults,
                // Going over the round limit is an error, so the last allowed round must end in an answer.
                allowMoreToolCalls = roundCount < maxRoundsForRequest
            )

            providerResponse = provider.continueWithToolResults(continueRequest)
        }

        val continuationCode = when (providerResponse) {
            is AiProviderResponse.FinalResponse -> "SUCCESS"
            is AiProviderResponse.Error -> (providerResponse as AiProviderResponse.Error).code
            else -> "UNKNOWN"
        }
        safeLog("[AI_ORCHESTRATION] readSucceeded=$readSucceeded writeCommitted=$writeCommitted continuationResult=$continuationCode")

        val currentPendingCheck = pendingMutationStore.getPendingMutation(conversationId) as? com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
        val hasOfferCaptured = currentPendingCheck != null &&
                currentPendingCheck.state == com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION &&
                currentPendingCheck.ingredients.isNotEmpty() &&
                !currentPendingCheck.instructions.isNullOrBlank()

        val isEligibleProposalIntent = (intentClass == com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.NEW_READ_OR_PROPOSAL_REQUEST ||
                intentClass == com.example.chatgpt_mealplanner.core.ai.context.UserMessageIntentClass.FOLLOW_UP_TO_ACTIVE_PROPOSAL)

        val finalResponseText = (providerResponse as? AiProviderResponse.FinalResponse)?.text ?: ""
        // Honour the user's explicit request even if Gemini formats its answer as one recipe.
        val responseIsMultiMealPlan = mealPlanRequest || isMultiMealResponse(finalResponseText)
        val replyNeedsRecipeCard = needsRecipeCard(
            userMessageText = userMessageText,
            replyText = finalResponseText,
            replyHasIngredients = (providerResponse as? AiProviderResponse.FinalResponse)?.proposal?.ingredients?.isNotEmpty() == true
        )

        if (!isRetailProductSearchOnlyRequest(userMessageText) && !responseIsMultiMealPlan && isEligibleProposalIntent && replyNeedsRecipeCard && !hasOfferCaptured && !plannerMutationCommitted && providerResponse is AiProviderResponse.FinalResponse) {
            safeLog("[STRUCTURED_OFFER_REPAIR] needed=true attempt=1")

            val repairRequest = request.copy(
                forcedToolName = AiToolId.OFFER_ADD_PLANNED_MEAL.toolName,
                messages = request.messages + listOfNotNull(
                    finalResponseText.takeIf { it.isNotBlank() }?.let { AiChatMessage(role = "assistant", content = it) },
                    AiChatMessage(
                        role = "user",
                        content = "[STRUCTURED_OFFER_REPAIR] Your last reply did not create a recipe card. Call 'offer_add_planned_meal' now. If your reply contained a recipe, keep that same dish, ingredients, quantities and method. Fill in anything missing so the title, servings, ingredients and method steps are complete."
                    )
                )
            )
            val repairResponse = provider.send(repairRequest)

            if (repairResponse is AiProviderResponse.ToolCalls) {
                var repairRound = 0
                var repairContinuation: AiProviderResponse = repairResponse
                while (repairContinuation is AiProviderResponse.ToolCalls && repairRound < 2) {
                    repairRound++
                    val repairCalls = (repairContinuation as AiProviderResponse.ToolCalls).calls
                    val repairToken = (repairContinuation as AiProviderResponse.ToolCalls).providerContinuationToken
                    val repairResults = mutableListOf<AiToolResult>()
                    for (c in repairCalls) {
                        safeLog("[AI_MODEL_TOOL_REQUEST] tool=${c.toolName} toolCallId=${c.toolCallId} phase=repair")
                        val r = toolDispatcher.executeToolCall(conversationId, authContext, c)
                        repairResults.add(r)
                    }
                    repairContinuation = provider.continueWithToolResults(
                        AiContinueWithToolsRequest(conversationId, repairToken, repairResults, allowMoreToolCalls = repairRound < 2)
                    )
                }
                if (repairContinuation is AiProviderResponse.FinalResponse) {
                    providerResponse = repairContinuation
                }
            }

            val postRepairPending = pendingMutationStore.getPendingMutation(conversationId) as? com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
            val repairSucceeded = postRepairPending != null &&
                    postRepairPending.state == com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION &&
                    postRepairPending.ingredients.isNotEmpty() &&
                    !postRepairPending.instructions.isNullOrBlank()

            safeLog("[STRUCTURED_OFFER_REPAIR] needed=true attempt=1 result=${if (repairSucceeded) "SUCCESS" else "FAILED"} pendingCreated=$repairSucceeded")

            if (!repairSucceeded) {
                safeLog("[STRUCTURED_OFFER_REPAIR] Failed to obtain valid offer after repair. Returning explicit failure response.")
                return AiOrchestrationResult.Error(
                    code = "INVALID_STRUCTURED_OFFER",
                    userFacingMessage = "Could not generate a complete structured recipe offer with full ingredients and cooking instructions. Please tap Retry."
                )
            }
        }

        return when (providerResponse) {
            is AiProviderResponse.FinalResponse -> {
                val finalRes = providerResponse as AiProviderResponse.FinalResponse
                val retailProductSearchOnly = isRetailProductSearchOnlyRequest(userMessageText)
                // A retailer lookup is not permission to generate a menu. Treat any structured
                // plan returned on a product-only turn as ineligible for the meal-plan UI.
                val responseIsMultiMealPlan = retailProductSearchOnly || mealPlanRequest ||
                        (finalRes.mealPlan?.size ?: 0) > 1 || isMultiMealResponse(finalRes.text)
                var validatedProposal = if (responseIsMultiMealPlan) {
                    null
                } else {
                    finalRes.proposal?.let { recipeValidator.validateProposal(it) }
                }

                var textToReturn = finalRes.text

                if (!plannerMutationCommitted) {
                    val origLen = textToReturn.length
                    textToReturn = sanitizeUncommittedPlannerClaims(textToReturn)
                    val newLen = textToReturn.length
                    safeLog("[RESPONSE_SANITIZATION] originalLength=$origLen sanitizedLength=$newLen removedCompletedWriteClaim=${origLen != newLen}")
                }

                var currentPending = pendingMutationStore.getPendingMutation(conversationId) as? com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
                if (retailProductSearchOnly) {
                    // A product lookup must not inherit or create a Save meal card from this
                    // conversation's prior proposal state.
                    activeProposalStore.clearActiveProposal(conversationId)
                    if (currentPending != null) {
                        pendingMutationStore.clearPendingMutation(conversationId, reason = "RETAIL_PRODUCT_LOOKUP_ONLY")
                        currentPending = null
                    }
                    validatedProposal = null
                    safeLog("[RETAIL_LOOKUP_UI_GUARD] clearedMealProposal=true")
                }
                val hasValidOfferAwaiting = currentPending != null && currentPending.state == com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION && currentPending.ingredients.isNotEmpty()

                if (hasValidOfferAwaiting && !responseIsMultiMealPlan) {
                    val pendingOffer = currentPending!!
                    textToReturn = formatAppOwnedRecipeProposal(pendingOffer)
                    // The text parser may have extracted only the bold title from the final prose,
                    // producing a card with zero ingredients. The structured offer captured by the
                    // tool dispatcher is authoritative for both the rendered card and Save action.
                    // The displayed recipe is formatted from this pending snapshot. Build the
                    // card and Save action from that same source so wording cannot drift between
                    // what the user reviewed and what is persisted.
                    val replacementRequest = userMessageText.contains("replacement recipe", ignoreCase = true) ||
                        userMessageText.contains("meal-change request", ignoreCase = true)
                    val modelChangeSummary = finalRes.proposal?.changeSummary?.takeIf { it.isNotBlank() }
                        ?: if (replacementRequest) {
                            finalRes.text.lineSequence()
                                .map { it.trim().removePrefix("#").trim() }
                                .firstOrNull { it.isNotBlank() && !it.startsWith("MEAL_PLAN_JSON:") }
                                ?.take(320)
                        } else null
                    val structuredProposal = proposalFromPendingSnapshot(pendingOffer).copy(
                        changeSummary = modelChangeSummary
                    )
                    if (structuredProposal.ingredients.isNotEmpty()) {
                        validatedProposal = recipeValidator.validateProposal(structuredProposal)
                        activeProposalStore.setActiveProposal(conversationId, structuredProposal)
                    }
                    safeLog("[PROPOSAL_STATE_PRECEDENCE] existingSource=\"STRUCTURED_TOOL\" incomingSource=\"FINAL_TEXT\" existingIngredientCount=${pendingOffer.ingredients.size} action=KEEP_EXISTING_DETERMINISTIC_DISPLAY")
                    safeLog("[OFFER_CAPTURE] Retained authoritative structured offer title=\"${pendingOffer.title}\" date=\"${pendingOffer.date}\" mealType=\"${pendingOffer.mealType?.name}\" ingredientCount=${pendingOffer.ingredients.size} state=AWAITING_CONFIRMATION reason=\"STRUCTURED_STATE_PRECEDENCE\"")
                } else {
                    val proposal = finalRes.proposal
                    val offeredAction = finalRes.proposedAction

                    safeLog("[PROPOSED_ACTION_RECEIVED] source=${if (offeredAction != null) "STRUCTURED_PROVIDER_FIELD" else "NONE"} actionType=${offeredAction?.javaClass?.simpleName ?: "NONE"} title=\"${proposal?.title}\" ingredientCount=${proposal?.ingredients?.size ?: 0} requestedDate=\"${proposal?.suggestedDate}\" requestedMealType=\"${proposal?.mealType}\"")

                    if (proposal != null) {
                        activeProposalStore.setActiveProposal(conversationId, proposal)
                        safeLog("[PROPOSAL_CAPTURE] conversationId=\"$conversationId\" title=\"${proposal.title}\" mealType=\"${proposal.mealType}\" suggestedDate=\"${proposal.suggestedDate}\" ingredientCount=${proposal.ingredients.size} stored=true")

                        if (!plannerMutationCommitted && offeredAction is com.example.chatgpt_mealplanner.contract.AiProposedAction.SaveMeal) {
                            val mappedIngs = proposal.ingredients.map {
                                com.example.chatgpt_mealplanner.core.model.MealIngredient(
                                    id = java.util.UUID.randomUUID().toString(),
                                    mealId = "",
                                    canonicalIngredientId = it.canonicalIngredientId,
                                    displayName = it.displayName,
                                    plannedQuantity = it.quantity,
                                    plannedUnit = com.example.chatgpt_mealplanner.core.model.parseQuantityUnit(it.unit)
                                )
                            }

                            val valRes = com.example.chatgpt_mealplanner.core.ai.validation.MealOfferValidator.validateStructuredMealOffer(
                                title = proposal.title,
                                servings = proposal.servings,
                                ingredients = mappedIngs
                            )
                            safeLog("[OFFER_VALIDATION] source=\"SaveMeal\" valid=${valRes.isValid} reason=\"${valRes.reason}\"")

                            if (valRes.isValid) {
                                val resolvedSlot = com.example.chatgpt_mealplanner.core.ai.resolver.MealSlotResolver.resolveSlot(
                                    requestedDateStr = proposal.suggestedDate,
                                    requestedMealTypeStr = proposal.mealType
                                )

                                val pendingAdd = com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal(
                                    title = proposal.title,
                                    date = resolvedSlot.date,
                                    mealType = resolvedSlot.mealType,
                                    servings = proposal.servings,
                                    ingredients = mappedIngs,
                                    sourceUserMessageId = userMsgId,
                                    state = com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION
                                )
                                pendingMutationStore.setPendingMutation(conversationId, pendingAdd, "SAVE_MEAL_ACTION")
                                safeLog("[OFFER_CAPTURE] conversationId=\"$conversationId\" actionType=\"ADD_PLANNED_MEAL\" title=\"${proposal.title}\" date=\"${resolvedSlot.date}\" mealType=\"${resolvedSlot.mealType.name}\" ingredientCount=${mappedIngs.size} state=AWAITING_CONFIRMATION stored=true reason=\"EXPLICIT_OFFER\"")
                            } else {
                                pendingMutationStore.clearPendingMutation(conversationId, "INVALID_OFFER_${valRes.reason}")
                                safeLog("[OFFER_CAPTURE] conversationId=\"$conversationId\" stored=false reason=\"INVALID_OFFER_${valRes.reason}\"")
                            }
                        } else if (!plannerMutationCommitted) {
                            pendingMutationStore.clearPendingMutation(conversationId, "PROPOSAL_ONLY_NO_EXPLICIT_OFFER")
                            safeLog("[OFFER_CAPTURE] conversationId=\"$conversationId\" stored=false reason=\"PROPOSAL_ONLY_NO_EXPLICIT_OFFER\"")
                        }
                    } else {
                        safeLog("[PROPOSAL_CAPTURE] conversationId=\"$conversationId\" stored=false reason=\"NO_PROPOSAL_IN_RESPONSE\"")
                        safeLog("[OFFER_CAPTURE] conversationId=\"$conversationId\" stored=false reason=\"NO_PROPOSAL_IN_RESPONSE\"")
                    }
                }

                val pendingAfter = pendingMutationStore.getPendingMutation(conversationId)
                if (pendingAfter != null && pendingAfter.state == com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION) {
                    textToReturn = when (pendingAfter) {
                        is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal -> formatAppOwnedRecipeProposal(pendingAfter)
                        is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem -> "Are you sure you want to delete **${pendingAfter.targetName}** from your inventory? Please confirm to proceed."
                        is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteAllInventory -> "Are you sure you want to delete all ${pendingAfter.totalItemCount} active items from your inventory? Please confirm to proceed."
                        is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.PrepareMarkMealCooked -> "Are you sure you want to mark **${pendingAfter.mealTitle}** as cooked and deduct ingredients from your inventory? Please confirm to proceed."
                    }
                    safeLog("[CONFIRMATION_PRESENTATION] pendingAwaitingConfirmation=true type=${pendingAfter.javaClass.simpleName} confirmationQuestionRendered=true")
                } else {
                    textToReturn = stripTrailingOfferQuestion(textToReturn)
                    safeLog("[CONFIRMATION_PRESENTATION] pendingAwaitingConfirmation=false confirmationQuestionRendered=false")
                }

                val finalResponseSource = when {
                    pendingAfter?.state == com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION -> "APP_CONFIRMATION"
                    writeCommitted -> "APP_MUTATION_SUCCESS"
                    failedWritesCount > 0 -> "APP_MUTATION_FAILURE"
                    else -> "MODEL"
                }

                val finalTraceLog = """
                    [DELETE_TRACE] final_response_source=$finalResponseSource
                    [DELETE_TRACE] final_response_text="$textToReturn"
                    [DELETE_TRACE] pending_at_return=${pendingAfter?.javaClass?.simpleName ?: "NONE"}
                """.trimIndent()
                safeLog(finalTraceLog)

                // Store the exact validated snapshot alongside the assistant message. The UI
                // reconstructs pending cards from this payload after process/app restarts.
                val validatedMealPlan = if (!retailProductSearchOnly) {
                    finalRes.mealPlan?.takeIf { it.size > 1 }?.map { recipeValidator.validateProposal(it) }
                } else null
                val proposalPayload = when {
                    validatedMealPlan != null -> ProposalMessagePayload(
                        kind = "MEAL_PLAN",
                        meals = validatedMealPlan
                    )
                    validatedProposal != null -> ProposalMessagePayload(
                        kind = "SINGLE_MEAL",
                        singleMeal = validatedProposal
                    )
                    else -> null
                }
                val structuredPayloadJson = proposalPayload?.let {
                    runCatching { Json.encodeToString(it) }.getOrNull()
                }
                val assistantMsgId = "msg_${System.currentTimeMillis()}"
                val assistantEntity = ChatMessageEntity(
                    id = assistantMsgId,
                    conversationId = conversationId,
                    role = "ASSISTANT",
                    // A proposal card replaces the model's prose to avoid showing it twice.
                    content = if (structuredPayloadJson != null) "" else textToReturn,
                    structuredPayloadJson = structuredPayloadJson,
                    modelId = finalRes.modelId,
                    timestamp = System.currentTimeMillis()
                )
                try {
                    chatRepository.saveMessage(assistantEntity)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Chat persistence failure does not roll back orchestration
                }

                val finalProp = activeProposalStore.getActiveProposal(conversationId)
                val finalPending = pendingMutationStore.getPendingMutation(conversationId)
                val finalPendingState = (finalPending as? com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal)?.state?.name ?: "NONE"
                safeLog("[AI_TURN_END] conversationId=\"$conversationId\" activeProposalPresent=${finalProp != null} pendingMutationPresent=${finalPending != null} pendingMutationState=$finalPendingState plannerMutationCommitted=$plannerMutationCommitted")

                AiOrchestrationResult.Final(
                    assistantText = textToReturn,
                    validatedProposal = validatedProposal,
                    mealPlan = if (retailProductSearchOnly) null else finalRes.mealPlan,
                    proposedAction = if (retailProductSearchOnly || isMealPlanRequest(userMessageText) || finalRes.mealPlan != null) {
                        null
                    } else if (hasValidOfferAwaiting) {
                        com.example.chatgpt_mealplanner.contract.AiProposedAction.SaveMeal(
                            proposalFromPendingSnapshot(currentPending!!)
                        )
                    } else {
                        finalRes.proposedAction
                    },
                    modelId = finalRes.modelId
                )
            }
            is AiProviderResponse.Error -> {
                val errRes = providerResponse as AiProviderResponse.Error
                if (writeCommitted) {
                    val fallbackText = "Your requested action was applied successfully, but Gemini is temporarily busy to generate a follow-up summary. Please check your screen."
                    val assistantMsgId = "msg_${System.currentTimeMillis()}"
                    try {
                        chatRepository.saveMessage(
                            ChatMessageEntity(
                                id = assistantMsgId,
                                conversationId = conversationId,
                                role = "ASSISTANT",
                                content = fallbackText,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {}

                    AiOrchestrationResult.Final(
                        assistantText = fallbackText,
                        validatedProposal = null,
                        proposedAction = null
                    )
                } else {
                    AiOrchestrationResult.Error(
                        code = errRes.code,
                        userFacingMessage = errRes.message
                    )
                }
            }
            else -> {
                if (writeCommitted) {
                    AiOrchestrationResult.Final(
                        assistantText = "Your action was applied successfully.",
                        validatedProposal = null,
                        proposedAction = null
                    )
                } else {
                    AiOrchestrationResult.Error("UNKNOWN", "Unknown provider response")
                }
            }
        }
    }

    /**
     * A recipe card is stored with empty text because the app draws it from its payload. Gemini
     * still has to read it, or "make it spicier" and "option 2" refer to nothing.
     */
    private fun historyTextFor(message: ChatMessageEntity): String? =
        message.content.takeIf { it.isNotBlank() }
            ?: message.structuredPayloadJson?.let(::describeProposalCard)

    private fun describeProposalCard(payloadJson: String): String? {
        val payload = runCatching { historyJson.decodeFromString<ProposalMessagePayload>(payloadJson) }.getOrNull()
            ?: return null
        val meals = listOfNotNull(payload.singleMeal).ifEmpty { payload.meals }.map { it.proposal }
        if (meals.isEmpty()) return null
        val saved = if (payload.state == "PENDING") "not saved yet" else "state: ${payload.state}"
        return buildString {
            appendLine("[Shown to the user as a card, $saved]")
            meals.forEachIndexed { index, meal ->
                if (meals.size > 1) append("Option ${index + 1}: ")
                appendLine("${meal.title} (${meal.servings} servings)${meal.suggestedDate?.let { " for $it" }.orEmpty()}")
                appendLine("Ingredients:")
                meal.ingredients.forEach { ingredient ->
                    val measure = ingredient.measure?.let { " ($it)" }.orEmpty()
                    val optional = if (ingredient.optional) " (optional)" else ""
                    appendLine("- ${ingredient.displayName}: ${ingredient.quantity} ${ingredient.unit}$measure$optional")
                }
                if (meal.steps.isNotEmpty()) {
                    appendLine("Method:")
                    meal.methodLines().forEach { appendLine(it) }
                }
            }
        }.trim()
    }

    private val historyJson = Json { ignoreUnknownKeys = true }

    private suspend fun saveAssistantMessage(conversationId: String, text: String) {
        val assistantMsgId = "msg_${System.currentTimeMillis()}"
        try {
            chatRepository.saveMessage(
                ChatMessageEntity(
                    id = assistantMsgId,
                    conversationId = conversationId,
                    role = "ASSISTANT",
                    content = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    private suspend fun executeDirectCancellation(
        conversationId: String,
        userMsgId: String,
        pending: com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation
    ): AiOrchestrationResult {
        val targetName = when (pending) {
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal -> pending.title
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem -> pending.targetName
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteAllInventory -> "active inventory items"
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.PrepareMarkMealCooked -> pending.mealTitle
        }
        pendingMutationStore.clearPendingMutation(conversationId, reason = "USER_CANCELLED_DIRECT")
        activeProposalStore.clearActiveProposal(conversationId)

        val userFacingText = "Cancelled action for **$targetName**. No changes were made to your data."
        saveAssistantMessage(conversationId, userFacingText)

        return AiOrchestrationResult.Final(
            assistantText = userFacingText,
            validatedProposal = null,
            proposedAction = null
        )
    }

    private suspend fun executeDirectConfirmation(
        conversationId: String,
        userMsgId: String,
        pending: com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation
    ): AiOrchestrationResult {
        safeLog("[CONFIRM_PENDING_MUTATION] found=true type=${pending.javaClass.simpleName} action=APP_OWNED_DIRECT_EXECUTION")

        return when (pending) {
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteInventoryItem -> {
                val authContext = ActionAuthorizationContext(
                    sourceUserMessageId = userMsgId,
                    userMessageText = "confirm",
                    scopes = listOf(AuthorizedMutationScope.DeleteInventoryScope(sourceUserMessageId = userMsgId, permittedItemId = pending.itemId))
                )
                val argsObj = kotlinx.serialization.json.buildJsonObject {
                    put("itemId", pending.itemId)
                }
                val toolCall = AiToolCall(
                    toolCallId = "call_direct_confirm_del_${System.currentTimeMillis()}",
                    toolName = "delete_inventory_item",
                    argsJson = argsObj.toString()
                )

                val res = toolDispatcher.executeToolCall(conversationId, authContext, toolCall)
                if (!res.success) {
                    return AiOrchestrationResult.Error(
                        code = res.errorCode ?: "CONFIRMATION_FAILED",
                        userFacingMessage = "I couldn't remove **${pending.targetName}** from your inventory. Tap Retry to try again."
                    )
                }
                pendingMutationStore.clearPendingMutation(conversationId, reason = "DIRECT_CONFIRM_SUCCESS")
                val text = "I have removed **${pending.targetName}** from your inventory."
                saveAssistantMessage(conversationId, text)
                AiOrchestrationResult.Final(assistantText = text)
            }
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.DeleteAllInventory -> {
                val authContext = ActionAuthorizationContext(
                    sourceUserMessageId = userMsgId,
                    userMessageText = "confirm",
                    scopes = listOf(AuthorizedMutationScope.DeleteAllInventoryScope(sourceUserMessageId = userMsgId))
                )
                val toolCall = AiToolCall(
                    toolCallId = "call_direct_confirm_del_all_${System.currentTimeMillis()}",
                    toolName = "delete_all_inventory",
                    argsJson = "{}"
                )

                val res = toolDispatcher.executeToolCall(conversationId, authContext, toolCall)
                if (!res.success) {
                    return AiOrchestrationResult.Error(
                        code = res.errorCode ?: "CONFIRMATION_FAILED",
                        userFacingMessage = "I couldn't clear your inventory. Tap Retry to try again."
                    )
                }
                pendingMutationStore.clearPendingMutation(conversationId, reason = "DIRECT_CONFIRM_SUCCESS")
                val text = "I have removed all active items from your inventory."
                saveAssistantMessage(conversationId, text)
                AiOrchestrationResult.Final(assistantText = text)
            }
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.PrepareMarkMealCooked -> {
                val authContext = ActionAuthorizationContext(
                    sourceUserMessageId = userMsgId,
                    userMessageText = "confirm"
                )
                val argsObj = kotlinx.serialization.json.buildJsonObject {
                    put("mealId", pending.mealId)
                }
                val toolCall = AiToolCall(
                    toolCallId = "call_direct_confirm_cook_${System.currentTimeMillis()}",
                    toolName = "prepare_mark_meal_cooked",
                    argsJson = argsObj.toString()
                )

                val res = toolDispatcher.executeToolCall(conversationId, authContext, toolCall)
                if (!res.success) {
                    return AiOrchestrationResult.Error(
                        code = res.errorCode ?: "CONFIRMATION_FAILED",
                        userFacingMessage = "I couldn't mark **${pending.mealTitle}** as cooked. Tap Retry to try again."
                    )
                }
                pendingMutationStore.clearPendingMutation(conversationId, reason = "DIRECT_CONFIRM_SUCCESS")
                val text = "Marked **${pending.mealTitle}** as cooked and updated inventory."
                saveAssistantMessage(conversationId, text)
                AiOrchestrationResult.Final(assistantText = text)
            }
            is com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal -> {
                // Recipe offers are accepted through the card's explicit Save action.
                val proposal = restorePendingMealProposal(conversationId)
                val text = "This recipe is ready to review. Use **Save meal** on the recipe card when you're ready; no changes have been saved yet."
                saveAssistantMessage(conversationId, text)
                AiOrchestrationResult.Final(
                    assistantText = text,
                    validatedProposal = proposal,
                    proposedAction = null
                )
            }
        }
    }

    private suspend fun executeDirectConfirmationAddMeal(
        conversationId: String,
        userMsgId: String,
        pending: com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
    ): AiOrchestrationResult {
        safeLog("[CONFIRM_PENDING_MUTATION] found=true title=\"${pending.title}\" date=\"${pending.date}\" mealType=\"${pending.mealType?.name}\" ingredientCount=${pending.ingredients.size}")

        val authContext = ActionAuthorizationContext(
            sourceUserMessageId = userMsgId,
            scopes = listOf(AuthorizedMutationScope.AddPlannedMealScope(sourceUserMessageId = userMsgId))
        )

        val argsObj = kotlinx.serialization.json.buildJsonObject {
            put("title", pending.title)
            pending.date?.let { put("date", it) }
            pending.mealType?.let { put("mealType", it.name) }
            put("servings", pending.servings)
            putJsonArray("ingredients") {
                pending.ingredients.forEach { ing ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("displayName", ing.displayName)
                        put("quantity", ing.plannedQuantity)
                        put("unit", ing.plannedUnit.name)
                        ing.canonicalIngredientId?.let { put("canonicalIngredientId", it) }
                    })
                }
            }
        }

        val toolCall = AiToolCall(
            toolCallId = "call_direct_confirm_${System.currentTimeMillis()}",
            toolName = "add_planned_meal",
            argsJson = argsObj.toString()
        )

        val res = toolDispatcher.executeToolCall(conversationId, authContext, toolCall)
        val success = res.success

        safeLog("[ADD_PLANNED_MEAL_COMMIT] title=\"${pending.title}\" date=\"${pending.date}\" mealType=\"${pending.mealType?.name}\" ingredientCount=${pending.ingredients.size} createCalled=true verified=$success success=$success")

        if (success) {
            pendingMutationStore.clearPendingMutation(conversationId, "USER_CONFIRMED_COMMITTED")
            activeProposalStore.clearActiveProposal(conversationId)

            val dateLabel = if (pending.date == java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)) "today's" else pending.date ?: "today's"
            val slotLabel = pending.mealType?.name?.lowercase() ?: "plan"
            val userFacingText = "Added **${pending.title}** to $dateLabel $slotLabel."

            val assistantMsgId = "msg_${System.currentTimeMillis()}"
            try {
                chatRepository.saveMessage(
                    ChatMessageEntity(
                        id = assistantMsgId,
                        conversationId = conversationId,
                        role = "ASSISTANT",
                        content = userFacingText,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}

            safeLog("[AI_TURN_END] conversationId=\"$conversationId\" activeProposalPresent=false pendingMutationPresent=false pendingMutationState=NONE plannerMutationCommitted=true")

            return AiOrchestrationResult.Final(
                assistantText = userFacingText,
                validatedProposal = null,
                proposedAction = null
            )
        } else {
            val userFacingText = "I couldn't add **${pending.title}** to your plan. Tap Retry to try again."
            safeLog("[AI_TURN_END] conversationId=\"$conversationId\" activeProposalPresent=true pendingMutationPresent=true pendingMutationState=AWAITING_CONFIRMATION plannerMutationCommitted=false")

            return AiOrchestrationResult.Error(
                code = "CONFIRMATION_FAILED",
                userFacingMessage = userFacingText
            )
        }
    }

    /**
     * Rehydrates a complete, still-unaccepted meal card from durable state after process death.
     * Only valid structured offers are restored; this never commits the proposed meal.
     */
    suspend fun restorePendingMealProposal(
        conversationId: String
    ): com.example.chatgpt_mealplanner.contract.ValidatedMealProposal? {
        val pending = pendingMutationStore.getPendingMutation(conversationId)
            as? com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
            ?: return null
        if (pending.state != com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION ||
            pending.ingredients.isEmpty() || pending.instructions.isNullOrBlank()
        ) return null

        // The pending offer also supplies the exact instructions shown in chat. Prefer it
        // during restoration so process death cannot reintroduce a different cached proposal.
        val proposal = proposalFromPendingSnapshot(pending)
        return recipeValidator.validateProposal(proposal)
    }

    /** Clear only a proposal after the user has saved it; never clear unrelated pending actions. */
    suspend fun clearPendingMealProposal(conversationId: String) {
        try {
            if (pendingMutationStore.getPendingMutation(conversationId) is
                com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
            ) {
                pendingMutationStore.clearPendingMutation(conversationId, "MEAL_CARD_SAVED")
            }
        } finally {
            activeProposalStore.clearActiveProposal(conversationId)
        }
    }

    private fun proposalFromPendingSnapshot(
        pending: com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
    ): com.example.chatgpt_mealplanner.contract.MealProposal {
        val steps = pending.instructions
            ?.lineSequence()
            ?.map { it.trim().replace(Regex("^(?:[-*•]\\s*|\\d+[.)]\\s*)"), "") }
            ?.filter { it.isNotBlank() }
            ?.mapIndexed { index, instruction ->
                com.example.chatgpt_mealplanner.contract.RecipeStep(order = index + 1, instruction = instruction)
            }
            ?.toList()
            .orEmpty()
        return com.example.chatgpt_mealplanner.contract.MealProposal(
            title = pending.title,
            mealType = pending.mealType?.name,
            suggestedDate = pending.date,
            servings = pending.servings,
            batchPortions = pending.servings,
            immediatePortions = pending.servings,
            leftoverPortions = 0,
            ingredients = pending.ingredients.map { ingredient ->
                val offered = pending.offer?.ingredients?.firstOrNull { it.displayName == ingredient.displayName }
                com.example.chatgpt_mealplanner.contract.ProposedIngredient(
                    canonicalIngredientId = ingredient.canonicalIngredientId,
                    displayName = ingredient.displayName,
                    quantity = ingredient.plannedQuantity,
                    unit = ingredient.plannedUnit.name,
                    optional = ingredient.optional,
                    measure = offered?.measure,
                    substitute = offered?.substitute
                )
            },
            steps = pending.offer?.steps?.takeIf { it.isNotEmpty() } ?: steps,
            prepMinutes = pending.offer?.prepMinutes,
            cookMinutes = pending.offer?.cookMinutes,
            equipment = pending.offer?.equipment.orEmpty(),
            reasoningSummary = pending.offer?.reasoningSummary.orEmpty()
        )
    }

    private fun formatAppOwnedRecipeProposal(
        pending: com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
    ): String {
        val sb = StringBuilder()
        sb.append("**${pending.title.trim()}**\n\n")

        val dateSlotStr = buildString {
            if (!pending.date.isNullOrBlank()) append("for ${pending.date} ")
            if (pending.mealType != null) append("(${pending.mealType.name.lowercase()}) ")
        }.trim()

        sb.append("Servings: ${pending.servings}")
        if (dateSlotStr.isNotBlank()) {
            sb.append(" • ").append(dateSlotStr)
        }
        sb.append("\n\n")

        sb.append("**Ingredients:**\n")
        pending.ingredients.forEach { ing ->
            val qtyFormatted = com.example.chatgpt_mealplanner.core.util.formatQuantityAndUnit(
                ing.plannedQuantity,
                ing.plannedUnit
            )
            sb.append("• ").append(ing.displayName)
            if (qtyFormatted.isNotBlank()) {
                sb.append(" — ").append(qtyFormatted)
            }
            sb.append("\n")
        }

        if (!pending.instructions.isNullOrBlank()) {
            sb.append("\n**Method:**\n")
            sb.append(pending.instructions.trim())
            sb.append("\n")
        }

        sb.append("\n\nReview this recipe card and select **Save meal** when you're ready. No changes have been saved yet.")

        return sb.toString().trim()
    }

    /**
     * The repair step is only for a reply that holds a recipe, or for a recipe request answered in
     * prose. Advice that mentions a recipe stays as it is, and so does the clarifying question the
     * system prompt asks for before a recipe with an unresolved choice.
     */
    private fun needsRecipeCard(userMessageText: String, replyText: String, replyHasIngredients: Boolean): Boolean {
        val reply = replyText.lowercase()
        val hasIngredientList = reply.contains("ingredient")
        val hasMethod = listOf("method", "instructions", "directions", "steps").any(reply::contains)
        if (replyHasIngredients || (hasIngredientList && hasMethod)) return true
        return asksForNewRecipe(userMessageText) && !isClarifyingQuestion(replyText)
    }

    private fun asksForNewRecipe(userMessageText: String): Boolean {
        val lower = intentTextWithoutNegatedGenerationInstructions(userMessageText)
        if (lower.contains("replacement recipe") || lower.contains("meal-change request")) return true
        if (recipeAboutExisting.containsMatchIn(lower)) return false
        return recipeRequest.containsMatchIn(lower) || whatToCook.containsMatchIn(lower)
    }

    private val recipeAboutExisting = Regex("""\b(this|that|my|saved|same)\s+(recipe|recipes|meal|dinner|lunch|breakfast)\b""")
    private val recipeRequest = Regex("""\b(give|suggest|recommend|want|need|find|show|get|create|generate|make|write|another|new|different|ideas?)\b.*\b(recipes?|meals?|dinner|lunch|breakfast|supper|tea)\b""")
    private val whatToCook = Regex("""\bwhat(?:'s| is| can| should| shall)?\s+(?:(?:i|we)\s+(?:cook|make|eat|have)|for\s+(?:dinner|lunch|breakfast|supper|tea))\b""")

    private fun isClarifyingQuestion(replyText: String): Boolean {
        val trimmed = replyText.trim()
        val offersARecipe = trimmed.contains("here is", ignoreCase = true) || trimmed.contains("here's", ignoreCase = true)
        return trimmed.endsWith("?") && !offersARecipe && stripTrailingOfferQuestion(trimmed) == trimmed
    }

    private fun stripTrailingOfferQuestion(text: String): String {
        val patterns = listOf(
            Regex("(?i)\\s*Would you like me to add [^?]+\\?\\s*$"),
            Regex("(?i)\\s*Shall I add [^?]+\\?\\s*$"),
            Regex("(?i)\\s*Should I put [^?]+\\?\\s*$"),
            Regex("(?i)\\s*Would you like to add this [^?]+\\?\\s*$"),
            Regex("(?i)\\s*Would you like me to put this on your meal plan[^?]*\\?\\s*$")
        )
        var cleaned = text
        for (pat in patterns) {
            cleaned = pat.replace(cleaned, "").trim()
        }
        return cleaned
    }

    /**
     * Negative instructions about what not to generate are constraints, not user intent.
     * For example, "search ASDA for milk; do not generate a recipe or meal plan" must
     * remain a retailer lookup and must not disable the retail-search tool.
     */
    private fun intentTextWithoutNegatedGenerationInstructions(userMessageText: String): String =
        userMessageText
            .replace(Regex("(?i)\\b(?:do not|don't|dont|never|avoid)\\s+(?:generate|create|make|include|produce|write)\\b[^.!?\\n]{0,120}"), "")
            .lowercase()
            .replace(Regex("[’‘]"), "'")

    private fun isRetailProductSearchOnlyRequest(userMessageText: String): Boolean {
        val lower = intentTextWithoutNegatedGenerationInstructions(userMessageText)
        val asksForRetailLookup = listOf("search", "look up", "lookup", "find", "show me", "list", "compare")
            .any(lower::contains) &&
            listOf("asda", "supermarket", "retailer", "product", "price", "prices", "milk", "bread", "eggs", "cheapest")
                .any(lower::contains)
        val asksForMealPlanning = listOf(
            "meal plan", "weekly plan", "weekly menu", "menu for the week", "plan for the week",
            "recipe", "recipes", "meal idea", "meal ideas", "what can i cook", "what can i make",
            "suggest a meal", "suggest dinner", "suggest lunch", "suggest breakfast"
        ).any(lower::contains)
        return asksForRetailLookup && !asksForMealPlanning
    }

    private fun isMealPlanRequest(userMessageText: String): Boolean {
        val lower = intentTextWithoutNegatedGenerationInstructions(userMessageText)
        val mentionsPlan = lower.contains("meal plan") ||
                lower.contains("weekly plan") ||
                lower.contains("weekly menu") ||
                lower.contains("menu for the week") ||
                lower.contains("plan for the week") ||
                lower.contains("week of meals") ||
                lower.contains("remainder of the week") ||
                lower.contains("rest of the week") ||
                lower.contains("remaining week") ||
                lower.contains("remaining days of the week") ||
                lower.contains("rest of this week") ||
                lower.contains("remainder of this week") ||
                lower.contains("meals for this week") ||
                lower.contains("meals for the week") ||
                lower.contains("this week's meals") ||
                lower.contains("this weeks meals") ||
                lower.contains("7-day plan") ||
                lower.contains("7 day plan") ||
                lower.contains("seven-day plan") ||
                lower.contains("seven day plan")
        return mentionsPlan
    }

    /**
     * Classify the assistant's actual output, not the wording of the user's request.
     * A multi-day/multi-meal answer must never be turned into the single-recipe Save card.
     */
    private fun isMultiMealResponse(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return false

        val dayHeadings = Regex(
            "(?i)^(?:#{1,6}\\s*)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:\\s*[:—–-].*)?$"
        )
        val daySections = lines.count { dayHeadings.matches(it) }
        if (daySections >= 2) return true

        val mealEntry = Regex(
            "(?i)^(?:[-*•]\\s*|\\d+[.)]\\s*|#{1,6}\\s*)?(?:(?:meal|option)\\s*\\d+\\s*[:.)—–-]?\\s*)?(?:(?:breakfast|brunch|lunch|dinner|tea|supper)\\s*[:—–-]\\s*).{3,}$"
        )
        if (lines.count { mealEntry.matches(it) } >= 2) return true

        // Do not classify a single recipe as a multi-meal plan just because its
        // ingredients or cooking steps use a numbered list. Multi-meal responses
        // are identified by multiple day headings or explicit meal-slot entries above.
        return false
    }

    private fun sanitizeUncommittedPlannerClaims(text: String): String {
        // Model prose is not evidence of a database write. Cover contractions,
        // plural meals and passive completion claims as well as "I have added".
        val normalized = text.replace("’", "'").replace(Regex("(?i)\\bI've\\b"), "I have")
        val completion = Regex("(?i)(?:I (?:have )?(?:now )?(?:added|scheduled|put|placed|saved)[^.!\n]*(?:meal|plan|dinner|week|calendar|lunch|breakfast|menu)|(?:meals?|dinners?|plan)[^.!\n]*(?:have been|has been|were|was|are now|is now) (?:added|saved|scheduled)|(?:added|saved|scheduled)[^.!\n]*(?:meal plan|planner|calendar)|(?:meal plan|planner) (?:is|has been) (?:updated|saved))")
        return if (completion.containsMatchIn(normalized)) {
            "No meal-plan changes were saved in this turn. Please retry or use Save meal on a recipe card when ready."
        } else text
    }

    private fun safeLog(msg: String) {
        val event = msg.substringBefore(']').takeIf { it.startsWith("[") }?.plus("]") ?: "[AI_EVENT]"
        try {
            android.util.Log.i("AI_MEALPLANNER", event)
        } catch (_: Throwable) {}
    }

    private fun containsPlanAdditionClaim(text: String): Boolean {
        val lower = text.lowercase()
        val additionKeywords = listOf(
            "add", "added", "adding", "schedule", "scheduled", "put", "saved", "planned", "created", "placed"
        )
        val planKeywords = listOf(
            "plan", "planner", "menu", "calendar", "schedule", "breakfast", "lunch", "dinner", "today", "tomorrow"
        )

        val hasAddition = additionKeywords.any { lower.contains(it) }
        val hasPlanContext = planKeywords.any { lower.contains(it) }

        return (hasAddition && hasPlanContext) ||
                lower.contains("added") ||
                lower.contains("scheduled") ||
                lower.contains("put that in") ||
                lower.contains("saved to") ||
                lower.contains("here is your updated plan")
    }

    private val SYSTEM_PROMPT = """
        You are Pantry Planner AI, an expert household meal planning and inventory assistant.
        Database is authoritative. Use tools for dynamic queries. Default servings to householdSize in the context, or 2 if it is not there.

        INTENT SEPARATION — IMPORTANT:
        1. Live retailer product searches, current online price lookups, and promotion checks are disabled. Do not call deprecated retailer catalogue tools to imply that a current price was checked.
        2. The app can record actual purchase prices locally, but only discuss a specific historical price when that observation is available in the current context. Otherwise say the price is unknown.
        3. A product lookup is NOT a request for a recipe or meal plan. Do not add a recipe, menu, or structured MEAL_PLAN_JSON unless the user explicitly asks for meal ideas, a recipe, or meal planning in the same message.
        4. Only return a structured multi-meal plan when the user explicitly requests a plan or multiple meals. Do not infer that request from prior conversation context.

        HOME-COUNTRY LOCALISATION MANDATE:
        The user's device locale indicates their home country is {{HOME_COUNTRY}}. Use that country's ordinary grocery names, spelling, ingredient terminology, and customary recipe units by default. Prefer ingredients and units that a typical shopper in that country can understand and buy (for example, distinguish plain/all-purpose flour, use locally standard names for vegetables and dairy, and use the country's normal volume/weight measures). Do not default to US or UK terminology unless that is the user's locale.
        Preserve a dish's authentic or origin-specific ingredients when they are intrinsic to it, even if they come from another country (for example, Parmigiano Reggiano/Parmesan remains an Italian cheese; do not localise it into an unrelated domestic substitute). If a country-specific term has a common local equivalent, use the local shopping name without changing the ingredient's identity.
        If the device locale has no country configured, default to UK English grocery terminology and metric quantities.

        INVENTORY-AWARE MEAL PLANNING:
        1. The usableInventory context contains only lots currently marked AVAILABLE, LOW, or USE_SOON. Never treat any item absent from that list as owned; OUT, DISCARDED, and NEEDS_CONFIRMATION stock is not usable.
        2. Inventory is optional planning context, not a requirement to build every meal around pantry stock. Prioritise appealing, coherent meals and use sensible fresh ingredients when they improve the dish. Do not force depleted or unsuitable ingredients into a recipe just to reduce shopping.
        3. AVAILABLE means the recorded quantity may cover a recipe; compare the quantity and unit against the recipe requirement rather than assuming any listed amount is sufficient. LOW means limited stock and must not be treated as fully available without sufficient recorded quantity. USE_SOON items are good candidates to use when they fit naturally.
        4. Never describe an ingredient as owned unless its usable stock quantity is sufficient. If stock is partial, uncertain, or insufficient, include the remaining amount in the shopping requirement.

        SUPERMARKET-COMPATIBLE INGREDIENT MANDATE:
        1. Every recipe ingredient must be a concrete, independently shoppable item with a specific display name. Never use bare placeholders such as "herbs", "spices", "seasoning", "vegetables", "meat", "sauce", "oil", "garnish", or "toppings". Name the actual ingredient or blend (for example, "dried mixed herbs", "ground cumin", "olive oil", or "fresh parsley") and provide a sensible quantity and unit. If the exact ingredient cannot be determined from the dish or user context, ask one short clarifying question or omit a non-essential garnish; do not emit an incomplete recipe ingredient.
        2. Use plain, specific grocery names from the home country in recipes and shopping lists. In the UK, for example, prefer familiar terms such as “double cream”, “bicarbonate of soda”, “black pepper”, “sweetcorn”, and “mature cheddar”. Avoid vague placeholders, invented product names, and redundant marketing or texture descriptors. When a recipe needs prepared meat, name the requested form explicitly, such as “diced chicken”.

        RECIPE QUALITY MANDATE:
        Write every recipe the way an experienced home cook would teach it, so that someone following it exactly gets a good result first time.
        1. FLAVOUR: Build a dish that tastes finished. Balance salt, fat, acid, sweetness and heat, include contrasting texture where it suits the dish, and say when to season and when to taste and adjust. Use techniques that add flavour, such as browning properly or toasting spices, when they suit the dish.
        2. METHOD: Put preparation before cooking and steps in the order they really happen, so that components finish together. Give one stage per step. For each cooking step state the heat level or oven temperature in °C, the time, and what to look for when it is ready (for example "until the edges are golden" or "until a knife slides in easily"). Include preheating, resting and draining when needed. Spell out every ingredient's use: an ingredient list and a method must agree, apart from water.
        3. QUANTITIES: Scale every quantity to the stated servings, using realistic portions (as a guide per person: 125-200 g meat or fish, 75-100 g dried pasta, 60-80 g dried rice). Keep strong seasonings in proportion. Give each ingredient's quantity as GRAM or MILLILITRE, or as UNIT, TIN or similar for whole items, because the app does its stock and shopping sums in those units. For small amounts also set measure to what the cook actually uses, such as "1 tsp" with 2 GRAM, and use that spoon amount in the method. Mention a good swap in substitute where one exists.
        4. TIME: Give honest prepMinutes and cookMinutes for a home cook, including chopping and heating up. Never understate them to make a recipe look quick.
        5. FOOD SAFETY: Say how to tell that chicken, turkey, pork, sausages and mince are cooked through, and give 75°C in the centre as the safe temperature when it helps. Never suggest undercooking them. Boil dried kidney beans hard for at least 10 minutes. When a recipe makes leftovers, say to cool them quickly and refrigerate them within 2 hours, use them within 2 days, and reheat them until piping hot. Cooked rice is stricter: cool it within 1 hour and use it within 1 day.
        6. VARIETY AND PREFERENCES: Follow the preferences in the context unless they would make the dish worse. Use recentCookedMeals and upcomingPlannedMeals to avoid repeating a dish, or the same main ingredient and cuisine, on neighbouring days unless the user asks for it.
        7. DIETS AND ALLERGIES: preferences.allergies and preferences.diets in the context, and any allergy, intolerance or diet the user states in chat, are strict rules for every ingredient, including those hidden in stock, sauces and spice blends. Never include a conflicting ingredient, even if the user's inventory contains it. Never guess that a product is safe; when an ingredient often contains an allergen, say to check the label. Avoid preferences.dislikes unless the user asks for that ingredient. If a request cannot be met within these rules, say so and offer the closest safe alternative. The app also checks ingredients and will return any conflict for you to fix.
        8. HEALTH: Do not give medical advice or medication dosing. If the user reports low blood glucose, do not apply low-carb meal planning; tell them to follow their usual treatment plan or contact a health professional.
        9. SKILL AND EQUIPMENT: Match the techniques and the amount of detail to preferences.skillLevel. If preferences.equipment is not empty, it lists special equipment the household has, such as an air fryer. Use it when it helps. Assume a normal kitchen otherwise.
        10. EXPLANATION: In whyThisSuits, give one short, specific reason this recipe suits this user today, such as which stock it uses up. Do not use marketing language or claim stock you cannot see.

        Live retailer catalogue search, online price lookup, promotions, branch discovery, and stock lookup are disabled. Do not call 'search_retail_products' to verify ingredient wording or claim that any product, price, or stock status has been checked. Users can record actual purchase prices against shops they define themselves; these are local historical observations, not live quotes. Never invent prices, availability, promotions, or savings. If a recorded price is discussed, qualify it with the recorded date and shop when available. If no recorded price exists, say the price is unknown rather than estimating it.

        APPLICATION STATE & WRITE TOOL MANDATE:
        1. You CANNOT change the user's Planner, Inventory, Shopping List, Staples, or other application state merely by stating in text that you did so.
        2. When the user explicitly asks for an application-state change (e.g., 'add option 2 to my planner', 'mark milk as out'), invoke the appropriate available write tool (e.g., add_planned_meal, mark_all_inventory_for_canonical_ingredient_out). Planned-meal changes automatically update shopping requirements; never ask to regenerate the shopping list.
        3. Do NOT tell the user an action was completed unless the corresponding tool result reports success.
        4. If no available tool can perform the requested action, clearly state that the action could not be performed rather than pretending it happened.

        AUTOMATED SHOPPING RECONCILIATION INVARIANT:
        1. Ingredients required for planned meals are derived automatically through the Shopping reconciliation system when meals are planned.
        2. Do NOT offer "Would you like me to generate your shopping list?", "Would you like me to add these recipe ingredients to Shopping?", or equivalent redundant shopping offers after proposing or adding a meal.
        3. Manual shopping tool 'add_manual_shopping_item' remains available for explicitly non-meal user items (e.g. 'add kitchen towels to my shopping list').

        STRUCTURED WHOLE-PLAN OUTPUT:
        Whenever the response contains multiple planned meals, append a valid JSON array at the very end using the exact prefix MEAL_PLAN_JSON:. Each object must include title, suggestedDate in YYYY-MM-DD format, mealType, servings, prepMinutes, cookMinutes, whyThisSuits, ingredients (objects with displayName, quantity, unit, measure, optional, substitutionAllowed, substitute), and steps (objects with order, instruction and durationMinutes). Every meal needs complete ingredients and cooking instructions. Do not wrap the JSON in markdown fences and put nothing after it. This structured payload drives the app's single Accept meal plan card; never call offer_add_planned_meal for each meal in a multi-meal plan.

        MEAL-PLAN WORKFLOW (DISTINCT FROM A SINGLE RECIPE):
        1. Treat requests to generate, create, suggest, or adjust a meal plan/menu for multiple days as a menu-level workflow. Present the complete plan as a readable day-by-day list and ask whether the user wants adjustments or wants to choose a meal to expand.
        2. Do NOT call 'offer_add_planned_meal' for any individual meal while responding to a meal-plan request. Do not create a recipe card for the first meal, and do not ask whether the user wants the full recipe for today's meal by default. The plan itself is the response; the user can choose a specific meal to expand in a later message.
        3. Do not save the proposed plan automatically. Only save meals when the user explicitly asks to add/save/schedule the plan, and use the appropriate supported write tools. If the app cannot save the whole plan atomically, explain the supported next step rather than implying all meals were saved.
        4. Do NOT ask whether to regenerate or update the shopping list as a routine follow-up to proposing or accepting a meal plan. Shopping-list regeneration is a separate action; only offer or perform it when the user asks for it or the app's explicit workflow requires it. Finish the meal-plan interaction without an unsolicited shopping-list question.
        5. For a single recipe or meal suggestion (not a multi-day plan), continue to follow the recipe-card workflow below.

        RECIPE CHANGE SUMMARY: When preparing a replacement recipe in response to a meal-change request, populate the proposal's changeSummary field with a concise, user-facing explanation of the specific requested change and how the replacement addresses it. Do not merely repeat the original request; state what is different in the proposed meal. For ordinary new recipes, changeSummary may be null.

        RECIPE PROPOSAL AND ADD OFFER MANDATE:
        1. Before creating a recipe card, inspect the proposed ingredients for unresolved user choices that affect what must be bought or how the dish is made. If a recipe idea depends on a choice such as "toppings of choice", ask one concise clarifying question listing sensible options and WAIT for the user's answer before calling 'offer_add_planned_meal'. Do not put vague placeholders such as "toppings of choice" into an ingredient name or combine them with a real ingredient (for example, never output "Heavy cream, toppings of choice"). Once the user answers, include the selected ingredients in the recipe; if the user declines to choose, omit the optional choice and keep the recipe complete.
        2. Optional garnish that does not materially affect shopping or preparation may simply be omitted or clearly marked optional; do not interrupt the user for trivial garnish choices.
        3. When suggesting a single recipe or meal idea, invoke 'offer_add_planned_meal' with complete parameters (title, optional date YYYY-MM-DD, optional mealType, servings, prepMinutes, cookMinutes, equipment, whyThisSuits, all ingredients, and every method stage as its own entry in steps).
           - Calling 'offer_add_planned_meal' DOES NOT modify the database or save the meal; it creates the recipe card shown by the app.
           - Do not ask whether to add the recipe or tell the user to confirm it in chat. The card has an explicit **Save meal** action.
           - If a valid offer is created, provide a short helpful summary; the app renders the recipe details and Save action.
        4. Only invoke 'add_planned_meal' for an explicit immediate-write request (e.g. "add Shakshuka to my plan"). A conversational "yes" or "sounds good" after a recipe card is shown is not a save command; direct the user to the card's **Save meal** action instead.

        REFERENTIAL SELECTION RULES:
        1. When you present the user with named or numbered choices/options (such as Option 1, Option 2, Option A, Option B, titles, or numbered list choices), and the user's next message selects or refers to one of those choices (e.g. 'option 2', 'the second one', 'first one', 'easy comfort food', 'that dinner', 'go with that', 'use that plan', 'the comfort food option'):
           - Treat the user's message as a SELECTION of the PREVIOUSLY PROPOSED option in conversation history.
           - PRESERVE the exact substantive contents (meals, recipes, menu items, ingredients, and titles) of that option unless the user explicitly asks you to modify, replace, regenerate, customize, or provide alternatives.
           - Do NOT interpret a selection message as a request to independently generate a new plan with the same theme or title.
        2. If the user asks for a modification (e.g. 'option 2 but change dinner to salmon'):
           - Keep the unchanged parts of Option 2 (e.g. breakfast and lunch) exactly as previously proposed, modifying only the requested meal.
        3. If the user asks for new options, alternatives, or explicitly asks to regenerate (e.g., 'give me another comfort food plan', 'I don't like those, show me different ideas', 'suggest something else'):
           - You MAY generate new suggestions or a new proposal.
        4. Conversational selection or approval of a proposed plan (e.g. 'option 2 sounds good', 'easy comfort food', 'let's do option 1') is a CONVERSATIONAL selection. Do NOT silently mutate the database or save to the planner unless the user explicitly requests a write/mutation action (e.g. 'add option 2 to my planner', 'save this meal', 'cook this meal', 'put this on my plan').
    """.trimIndent()
}
