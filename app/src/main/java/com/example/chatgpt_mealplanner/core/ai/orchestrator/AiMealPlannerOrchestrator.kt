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
        userMessageId: String? = null
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
                        content = userMessageText,
                        timestamp = System.currentTimeMillis()
                    )
                )
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
            chatRepository.getMessagesForConversation(conversationId).takeLast(20).map {
                AiChatMessage(role = it.role.lowercase(), content = it.content)
            }
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
            systemPrompt = SYSTEM_PROMPT,
            contextPayloadJson = contextPayloadJson,
            contextPayload = contextPayload,
            messages = historicalMessages,
            availableTools = AiToolId.entries.toList()
        )

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
            if (roundCount >= MAX_TOOL_ROUNDS) {
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
                toolResults = toolResults
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
        val textSuggestsRecipeProposal = finalResponseText.contains("recipe", ignoreCase = true) ||
                finalResponseText.contains("ingredients", ignoreCase = true) ||
                finalResponseText.contains("instructions", ignoreCase = true) ||
                finalResponseText.contains("method", ignoreCase = true) ||
                (providerResponse as? AiProviderResponse.FinalResponse)?.proposal != null

        if (isEligibleProposalIntent && textSuggestsRecipeProposal && !hasOfferCaptured && !plannerMutationCommitted && providerResponse is AiProviderResponse.FinalResponse) {
            safeLog("[STRUCTURED_OFFER_REPAIR] needed=true attempt=1")

            val repairRequest = request.copy(
                forcedToolName = AiToolId.OFFER_ADD_PLANNED_MEAL.toolName,
                messages = request.messages + listOf(
                    AiChatMessage(
                        role = "user",
                        content = "[STRUCTURED_OFFER_REPAIR] The structured recipe offer was rejected because ingredients or instructions were missing or empty. Call 'offer_add_planned_meal' again now with COMPLETE recipe parameters: title, servings, all ingredients array, and complete cooking instructions."
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
                        AiContinueWithToolsRequest(conversationId, repairToken, repairResults)
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

            if (!repairSucceeded && isEligibleProposalIntent && textSuggestsRecipeProposal) {
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
                var validatedProposal = finalRes.proposal?.let { recipeValidator.validateProposal(it) }

                var textToReturn = finalRes.text

                if (!plannerMutationCommitted) {
                    val origLen = textToReturn.length
                    textToReturn = sanitizeUncommittedPlannerClaims(textToReturn)
                    val newLen = textToReturn.length
                    safeLog("[RESPONSE_SANITIZATION] originalLength=$origLen sanitizedLength=$newLen removedCompletedWriteClaim=${origLen != newLen}")
                }

                val currentPending = pendingMutationStore.getPendingMutation(conversationId) as? com.example.chatgpt_mealplanner.core.ai.pending.PendingAiMutation.AddPlannedMeal
                val hasValidOfferAwaiting = currentPending != null && currentPending.state == com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationState.AWAITING_CONFIRMATION && currentPending.ingredients.isNotEmpty()

                if (hasValidOfferAwaiting) {
                    val pendingOffer = currentPending!!
                    textToReturn = formatAppOwnedRecipeProposal(pendingOffer)
                    // The text parser may have extracted only the bold title from the final prose,
                    // producing a card with zero ingredients. The structured offer captured by the
                    // tool dispatcher is authoritative for both the rendered card and Save action.
                    val structuredProposal = activeProposalStore.getActiveProposal(conversationId)
                        ?.takeIf { it.ingredients.isNotEmpty() }
                        ?: proposalFromPendingSnapshot(pendingOffer)
                    if (structuredProposal.ingredients.isNotEmpty()) {
                        validatedProposal = recipeValidator.validateProposal(structuredProposal)
                        activeProposalStore.setActiveProposal(conversationId, structuredProposal)
                    }
                    safeLog("[PROPOSAL_STATE_PRECEDENCE] existingSource=\"STRUCTURED_TOOL\" incomingSource=\"FINAL_TEXT\" existingIngredientCount=${currentPending.ingredients.size} action=KEEP_EXISTING_DETERMINISTIC_DISPLAY")
                    safeLog("[OFFER_CAPTURE] Retained authoritative structured offer title=\"${currentPending.title}\" date=\"${currentPending.date}\" mealType=\"${currentPending.mealType?.name}\" ingredientCount=${currentPending.ingredients.size} state=AWAITING_CONFIRMATION reason=\"STRUCTURED_STATE_PRECEDENCE\"")
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

                val assistantMsgId = "msg_${System.currentTimeMillis()}"
                val assistantEntity = ChatMessageEntity(
                    id = assistantMsgId,
                    conversationId = conversationId,
                    role = "ASSISTANT",
                    content = textToReturn,
                    modelId = finalRes.modelId,
                    timestamp = System.currentTimeMillis()
                )
                try {
                    chatRepository.saveMessage(assistantEntity)
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
                    proposedAction = finalRes.proposedAction,
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
                executeDirectConfirmationAddMeal(conversationId, userMsgId, pending)
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
                com.example.chatgpt_mealplanner.contract.ProposedIngredient(
                    canonicalIngredientId = ingredient.canonicalIngredientId,
                    displayName = ingredient.displayName,
                    quantity = ingredient.plannedQuantity,
                    unit = ingredient.plannedUnit.name
                )
            },
            steps = steps,
            reasoningSummary = "Structured recipe offer"
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

        val dateLabel = if (pending.date == java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)) "today's" else pending.date ?: "today's"
        val slotLabel = pending.mealType?.name?.lowercase() ?: "meal plan"
        sb.append("\nWould you like me to add **${pending.title.trim()}** to $dateLabel $slotLabel?")

        return sb.toString().trim()
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

    private fun sanitizeUncommittedPlannerClaims(text: String): String {
        // Model prose is not evidence of a database write. Cover contractions,
        // plural meals and passive completion claims as well as "I have added".
        val normalized = text.replace("’", "'").replace(Regex("(?i)\\bI've\\b"), "I have")
        val completion = Regex("(?i)(?:I (?:have )?(?:now )?(?:added|scheduled|put|placed|saved)[^.!\n]*(?:meal|plan|dinner|week|calendar|lunch|breakfast|menu)|(?:meals?|dinners?|plan)[^.!\n]*(?:have been|has been|were|was|are now|is now) (?:added|saved|scheduled)|(?:added|saved|scheduled)[^.!\n]*(?:meal plan|planner|calendar)|(?:meal plan|planner) (?:is|has been) (?:updated|saved))")
        return if (completion.containsMatchIn(normalized)) {
            "No meal-plan changes were saved in this turn. Please ask me to try again or confirm a meal proposal before adding it."
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
        Database is authoritative. Use tools for dynamic queries. Household size default is 2.

        UK LOCALISATION MANDATE:
        1. Always use UK English spelling and UK grocery terminology (e.g. 'tinned tomatoes' instead of 'canned tomatoes', 'aubergine', 'courgette', 'coriander', 'spring onion', 'icing sugar').
        2. Use metric quantities (grams, kg, ml, litres).

        APPLICATION STATE & WRITE TOOL MANDATE:
        1. You CANNOT change the user's Planner, Inventory, Shopping List, Staples, or other application state merely by stating in text that you did so.
        2. When the user explicitly asks for an application-state change (e.g. 'add option 2 to my planner', 'regenerate shopping list', 'mark milk as out'), you MUST invoke the appropriate write tool (e.g., add_planned_meal, regenerate_shopping_list, mark_all_inventory_for_canonical_ingredient_out).
        3. Do NOT tell the user an action was completed unless the corresponding tool result reports success.
        4. If no available tool can perform the requested action, clearly state that the action could not be performed rather than pretending it happened.

        AUTOMATED SHOPPING RECONCILIATION INVARIANT:
        1. Ingredients required for planned meals are derived automatically through the Shopping reconciliation system when meals are planned.
        2. Do NOT offer "Would you like me to generate your shopping list?", "Would you like me to add these recipe ingredients to Shopping?", or equivalent redundant shopping offers after proposing or adding a meal.
        3. Manual shopping tool 'add_manual_shopping_item' remains available for explicitly non-meal user items (e.g. 'add kitchen towels to my shopping list').

        RECIPE PROPOSAL AND ADD OFFER MANDATE:
        1. When suggesting a recipe or meal idea, describe the dish clearly in text.
        2. If you want to offer to add the meal to the user's plan (e.g. "Would you like me to add Shakshuka to your meal plan for today's lunch?"), invoke the 'offer_add_planned_meal' tool with complete parameters (title, optional date YYYY-MM-DD, optional mealType, servings, ingredients).
           - Calling 'offer_add_planned_meal' DOES NOT modify the database or save the meal; it prepares a structured offer for user confirmation.
           - Do NOT call 'add_planned_meal' when suggesting a recipe or asking if the user wants to add it. Use 'offer_add_planned_meal'.
        3. Only invoke 'add_planned_meal' when the user explicitly requests an immediate write (e.g. 'add Shakshuka to my plan') or confirms an offered meal (e.g. 'yes', 'sure', 'add it').

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
