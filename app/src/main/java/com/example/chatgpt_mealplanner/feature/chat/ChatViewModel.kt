package com.example.chatgpt_mealplanner.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProposedAction
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ValidatedMealProposal
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealTag
import com.example.chatgpt_mealplanner.core.repository.ChatRepository
import com.example.chatgpt_mealplanner.core.repository.MealRepository
import com.example.chatgpt_mealplanner.core.repository.MealChangeRequestRepository
import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import com.example.chatgpt_mealplanner.core.service.MealDomainService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private data class RequestStateHolder(
    val requestState: ChatRequestState = ChatRequestState.IDLE,
    val retainedUserMessage: String? = null,
    val retainedUserMessageId: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val orchestrator: AiMealPlannerOrchestrator,
    private val chatRepository: ChatRepository,
    private val mealDomainService: MealDomainService,
    private val mealRepository: MealRepository,
    private val mealChangeRequestRepository: MealChangeRequestRepository
) : ViewModel() {

    private val conversationIdFlow = MutableStateFlow("default_conversation")
    private val userMessageTextFlow = MutableStateFlow("")
    private val errorMessageFlow = MutableStateFlow<String?>(null)
    private val requestStateHolderFlow = MutableStateFlow(RequestStateHolder())

    private val elapsedSecondsFlow = MutableStateFlow(0)
    private val progressStatusTextFlow = MutableStateFlow<String?>(null)
    private var progressTickerJob: Job? = null

    private val mealChangeRequestIdFlow = MutableStateFlow<String?>(null)
    private val latestProposalFlow = MutableStateFlow<ValidatedMealProposal?>(null)
    private val latestProposedActionFlow = MutableStateFlow<AiProposedAction?>(null)

    private val showCookConfirmationDialogFlow = MutableStateFlow(false)
    private val cookConfirmationMealIdFlow = MutableStateFlow<String?>(null)
    private val cookConfirmationMealTitleFlow = MutableStateFlow<String?>(null)

    private val messagesFlow = chatRepository.observeMessagesForConversation("default_conversation")

    val uiState: StateFlow<ChatUiState> = combine(
        conversationIdFlow,
        messagesFlow,
        latestProposalFlow,
        latestProposedActionFlow,
        requestStateHolderFlow,
        userMessageTextFlow,
        errorMessageFlow,
        elapsedSecondsFlow,
        progressStatusTextFlow,
        showCookConfirmationDialogFlow,
        cookConfirmationMealIdFlow,
        cookConfirmationMealTitleFlow
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val reqHolder = flows[4] as RequestStateHolder
        val isReqInFlight = reqHolder.requestState == ChatRequestState.IN_FLIGHT

        ChatUiState(
            activeConversationId = flows[0] as String,
            messages = flows[1] as List<ChatMessageEntity>,
            latestProposal = flows[2] as ValidatedMealProposal?,
            latestProposedAction = flows[3] as AiProposedAction?,
            isLoading = isReqInFlight,
            elapsedSeconds = flows[7] as Int,
            progressStatusText = flows[8] as String?,
            userMessageText = flows[5] as String,
            errorMessage = flows[6] as String?,
            requestState = reqHolder.requestState,
            retainedUserMessage = reqHolder.retainedUserMessage,
            showCookConfirmationDialog = flows[9] as Boolean,
            cookConfirmationMealId = flows[10] as String?,
            cookConfirmationMealTitle = flows[11] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )

    init {
        ensureDefaultConversationExists()
    }

    private fun ensureDefaultConversationExists() {
        viewModelScope.launch {
            val existing = chatRepository.getConversationById("default_conversation")
            if (existing == null) {
                chatRepository.createOrUpdateConversation(
                    ChatConversationEntity(
                        id = "default_conversation",
                        title = "Pantry Assistant",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun prepareMealChangeRequest(request: MealChangeRequestEntity) {
        mealChangeRequestIdFlow.value = request.id
        userMessageTextFlow.value = "Please adjust the existing planned meal to address this follower request: \"${request.requestText}\". Keep it as a replacement for the same planned meal, retain its planned date and meal type, and provide a complete adjusted recipe with ingredients and ordered cooking steps. Do not create a separate meal."
        errorMessageFlow.value = null
    }

    fun onEvent(event: ChatUiEvent) {
        when (event) {
            is ChatUiEvent.UpdateMessageText -> userMessageTextFlow.value = event.text
            ChatUiEvent.SendMessage -> sendMessage()
            ChatUiEvent.RetryLastFailedMessage -> retryLastFailedMessage()
            is ChatUiEvent.SaveMealProposalToPlan -> saveMealProposalToPlan(event.proposal)
            is ChatUiEvent.ConfirmCookedMeal -> confirmCookedMeal(event.mealId)
            ChatUiEvent.DismissCookConfirmation -> dismissCookConfirmation()
            ChatUiEvent.ClearErrorMessage -> errorMessageFlow.value = null
        }
    }

    private fun safeLog(msg: String) {
        try {
            android.util.Log.i("AI_MEALPLANNER", msg)
        } catch (_: Throwable) {}
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        elapsedSecondsFlow.value = 0
        progressStatusTextFlow.value = "Reading pantry context..."
        progressTickerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val current = elapsedSecondsFlow.value + 1
                elapsedSecondsFlow.value = current
                progressStatusTextFlow.value = when {
                    current < 5 -> "Reading pantry context (${current}s)..."
                    current < 25 -> "Calling AI assistant (${current}s)..."
                    current < 45 -> "Processing meal actions (${current}s)..."
                    else -> "Still waiting for AI response (${current}s)..."
                }
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = null
        elapsedSecondsFlow.value = 0
        progressStatusTextFlow.value = null
    }

    private fun transitionRequestState(
        toState: ChatRequestState,
        retainedMessage: String? = null,
        retainedMessageId: String? = null,
        reason: String? = null
    ) {
        val fromState = requestStateHolderFlow.value.requestState
        requestStateHolderFlow.value = RequestStateHolder(toState, retainedMessage, retainedMessageId)
        if (toState != ChatRequestState.IN_FLIGHT) {
            stopProgressTicker()
        }
        if (reason != null) {
            safeLog("[CHAT_REQUEST_STATE] from=$fromState to=$toState reason=\"$reason\"")
        } else {
            safeLog("[CHAT_REQUEST_STATE] from=$fromState to=$toState")
        }
    }

    private fun sendMessage() {
        val text = userMessageTextFlow.value.trim()
        if (text.isBlank()) return
        if (requestStateHolderFlow.value.requestState == ChatRequestState.IN_FLIGHT) return

        safeLog("[CHAT_SEND] text=\"$text\"")

        val userMsgId = "msg_${System.currentTimeMillis()}"
        userMessageTextFlow.value = ""
        errorMessageFlow.value = null
        startProgressTicker()
        transitionRequestState(ChatRequestState.IN_FLIGHT, retainedMessage = text, retainedMessageId = userMsgId)

        viewModelScope.launch {
            safeLog("[CHAT_ORCHESTRATOR_CALL] conversationId=\"default_conversation\" text=\"$text\" messageId=\"$userMsgId\"")
            val result = orchestrator.processUserMessage("default_conversation", text, userMessageId = userMsgId)

            when (result) {
                is AiOrchestrationResult.Final -> {
                    transitionRequestState(ChatRequestState.SUCCEEDED, retainedMessage = null, retainedMessageId = null)
                    latestProposalFlow.value = result.validatedProposal
                    latestProposedActionFlow.value = result.proposedAction

                    val confirmAction = result.proposedAction as? AiProposedAction.ConfirmCookedMeal
                    if (confirmAction != null) {
                        showCookConfirmationDialogFlow.value = true
                        cookConfirmationMealIdFlow.value = confirmAction.mealId
                        cookConfirmationMealTitleFlow.value = confirmAction.mealTitle
                    }
                }
                is AiOrchestrationResult.Error -> {
                    errorMessageFlow.value = result.userFacingMessage
                    transitionRequestState(ChatRequestState.FAILED_RETRYABLE, retainedMessage = text, retainedMessageId = userMsgId, reason = result.userFacingMessage)
                }
            }
        }
    }

    private fun retryLastFailedMessage() {
        val currentHolder = requestStateHolderFlow.value
        if (currentHolder.requestState == ChatRequestState.IN_FLIGHT) return
        val text = currentHolder.retainedUserMessage ?: return
        val messageId = currentHolder.retainedUserMessageId

        safeLog("[CHAT_RETRY] text=\"$text\" messageId=\"$messageId\"")

        errorMessageFlow.value = null
        startProgressTicker()
        transitionRequestState(ChatRequestState.IN_FLIGHT, retainedMessage = text, retainedMessageId = messageId)

        viewModelScope.launch {
            val result = orchestrator.processUserMessage("default_conversation", text, userMessageId = messageId)

            when (result) {
                is AiOrchestrationResult.Final -> {
                    transitionRequestState(ChatRequestState.SUCCEEDED, retainedMessage = null, retainedMessageId = null)
                    latestProposalFlow.value = result.validatedProposal
                    latestProposedActionFlow.value = result.proposedAction

                    val confirmAction = result.proposedAction as? AiProposedAction.ConfirmCookedMeal
                    if (confirmAction != null) {
                        showCookConfirmationDialogFlow.value = true
                        cookConfirmationMealIdFlow.value = confirmAction.mealId
                        cookConfirmationMealTitleFlow.value = confirmAction.mealTitle
                    }
                }
                is AiOrchestrationResult.Error -> {
                    errorMessageFlow.value = result.userFacingMessage
                    transitionRequestState(ChatRequestState.FAILED_RETRYABLE, retainedMessage = text, retainedMessageId = messageId, reason = result.userFacingMessage)
                }
            }
        }
    }

    private fun saveMealProposalToPlan(proposal: MealProposal) {
        viewModelScope.launch {
            if (proposal.ingredients.isEmpty()) {
                errorMessageFlow.value = "Cannot save a proposal without ingredients. Please ask AI to suggest complete ingredients first."
                return@launch
            }

            val resolvedSlot = com.example.chatgpt_mealplanner.core.ai.resolver.MealSlotResolver.resolveSlot(
                requestedDateStr = proposal.suggestedDate,
                requestedMealTypeStr = proposal.mealType
            )
            val parsedTags = proposal.tags.mapNotNull { runCatching { MealTag.valueOf(it.uppercase()) }.getOrNull() }.toSet()

            val changeRequestId = mealChangeRequestIdFlow.value
            val linkedRequest = if (changeRequestId != null) {
                mealChangeRequestRepository.observeAllRequests().firstOrNull()?.firstOrNull { it.id == changeRequestId }
            } else null
            if (changeRequestId != null && (linkedRequest == null || linkedRequest.status != "IN_PROGRESS")) {
                errorMessageFlow.value = "This meal change request is no longer available. Reopen it from the planner and try again."
                return@launch
            }
            val existingMeal = linkedRequest?.let { mealRepository.getMealWithIngredients(it.mealId)?.meal }
            if (linkedRequest != null && existingMeal == null) {
                errorMessageFlow.value = "The planned meal could not be found, so the request was left open."
                return@launch
            }
            val stepText = proposal.steps.sortedBy { it.order }.joinToString("\\n") { "${it.order}. ${it.instruction}" }.ifBlank { existingMeal?.instructions }
            val meal = if (existingMeal != null) {
                existingMeal.copy(
                    title = proposal.title,
                    servings = proposal.servings,
                    batchPortions = proposal.batchPortions,
                    immediatePortions = proposal.immediatePortions,
                    leftoverPortions = proposal.leftoverPortions,
                    cookInTwoBatches = proposal.cookInTwoBatches,
                    tags = parsedTags,
                    instructions = stepText,
                    updatedAt = System.currentTimeMillis()
                )
            } else Meal(
                id = UUID.randomUUID().toString(),
                date = resolvedSlot.date,
                mealType = resolvedSlot.mealType,
                title = proposal.title,
                servings = proposal.servings,
                batchPortions = proposal.batchPortions,
                immediatePortions = proposal.immediatePortions,
                leftoverPortions = proposal.leftoverPortions,
                cookInTwoBatches = proposal.cookInTwoBatches,
                tags = parsedTags,
                instructions = stepText
            )

            val ingredients = proposal.ingredients.map {
                val parsedUnit = com.example.chatgpt_mealplanner.core.model.parseQuantityUnit(it.unit)
                MealIngredient(
                    id = UUID.randomUUID().toString(),
                    mealId = meal.id,
                    canonicalIngredientId = it.canonicalIngredientId,
                    displayName = it.displayName,
                    plannedQuantity = it.quantity,
                    plannedUnit = parsedUnit,
                    optional = it.optional,
                    substitutionAllowed = it.substitutionAllowed
                )
            }

            val createResult = if (existingMeal != null) {
                mealDomainService.replaceMeal(existingMeal.id, meal, ingredients)
            } else {
                mealDomainService.createPlannedMeal(meal, ingredients)
            }

            when (createResult) {
                is DomainResult.Success -> {
                    latestProposalFlow.value = null
                    if (linkedRequest != null) {
                        mealChangeRequestRepository.updateRequestStatus(
                            linkedRequest.id,
                            "COMPLETED",
                            linkedRequest.mainResponse ?: "Updated meal: ${meal.title}"
                        )
                        mealChangeRequestIdFlow.value = null
                        errorMessageFlow.value = "Updated '${meal.title}' and completed the change request."
                    } else {
                        errorMessageFlow.value = "Saved '${meal.title}' to meal plan for ${meal.date}!"
                    }
                }
                is DomainResult.Failure -> {
                    errorMessageFlow.value = formatDomainError(createResult.error)
                }
            }
        }
    }

    private fun confirmCookedMeal(mealId: String) {
        viewModelScope.launch {
            val cookResult = mealDomainService.markMealCooked(mealId)
            dismissCookConfirmation()

            when (cookResult) {
                is DomainResult.Success -> {
                    errorMessageFlow.value = "Marked meal cooked and deducted ingredients!"
                }
                is DomainResult.Failure -> {
                    errorMessageFlow.value = formatDomainError(cookResult.error)
                }
            }
        }
    }

    private fun dismissCookConfirmation() {
        showCookConfirmationDialogFlow.value = false
        cookConfirmationMealIdFlow.value = null
        cookConfirmationMealTitleFlow.value = null
    }

    private fun formatDomainError(error: DomainError): String {
        return when (error) {
            is DomainError.InsufficientQuantity -> "Insufficient stock: ${error.requested}"
            is DomainError.InvalidUnitConversion -> "Unit conversion error"
            is DomainError.NeedsConfirmation -> "Confirmation required: ${error.displayName}"
            is DomainError.TransactionFailed -> "Transaction error: ${error.cause}"
            is DomainError.ItemNotFound -> "Item not found: ${error.itemId}"
            is DomainError.InvalidQuantity -> "Invalid quantity: ${error.quantity}"
            is DomainError.ItemNotUsable -> "Item not available: ${error.itemId}"
            else -> "Operation failed"
        }
    }
}
