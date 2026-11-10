package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.contract.AiOrchestrationResult
import com.example.chatgpt_mealplanner.contract.AiProposedAction
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposalMessagePayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private object UnavailableMealRepository : MealRepository {
    override fun observeMealsForDate(date: String) = kotlinx.coroutines.flow.emptyFlow<List<com.example.chatgpt_mealplanner.core.model.MealWithIngredients>>()
    override fun observeMealsForWeek(startDate: String, endDate: String) = kotlinx.coroutines.flow.emptyFlow<List<com.example.chatgpt_mealplanner.core.model.MealWithIngredients>>()
    override fun observeAllMealTitles() = kotlinx.coroutines.flow.emptyFlow<Map<String, String>>()
    override suspend fun getMealWithIngredients(mealId: String): com.example.chatgpt_mealplanner.core.model.MealWithIngredients? = null
    override suspend fun insertMealWithIngredients(meal: Meal, ingredients: List<MealIngredient>) = error("Meal repository is unavailable")
    override suspend fun updateMeal(meal: Meal) = error("Meal repository is unavailable")
    override suspend fun setMealRating(mealId: String, rating: com.example.chatgpt_mealplanner.core.model.MealRating?) = error("Meal repository is unavailable")
    override suspend fun updateMealIngredients(mealId: String, ingredients: List<MealIngredient>) = error("Meal repository is unavailable")
    override suspend fun deleteMeal(mealId: String) = error("Meal repository is unavailable")
    override suspend fun getRecentCookedMeals(daysLookback: Int) = emptyList<com.example.chatgpt_mealplanner.core.model.MealWithIngredients>()
    override suspend fun getUpcomingPlannedMeals(startDate: String, endDate: String) = emptyList<com.example.chatgpt_mealplanner.core.model.MealWithIngredients>()
}

private object UnavailableMealChangeRequestRepository : MealChangeRequestRepository {
    override fun observeRequestsForMeal(mealId: String) = kotlinx.coroutines.flow.emptyFlow<List<MealChangeRequestEntity>>()
    override fun observeAllRequests() = kotlinx.coroutines.flow.emptyFlow<List<MealChangeRequestEntity>>()
    override suspend fun submitRequest(mealId: String, requestText: String) = error("Meal request repository is unavailable")
    override suspend fun updateRequestStatus(requestId: String, status: String, mainResponse: String?) = error("Meal request repository is unavailable")
}

private data class RequestStateHolder(
    val requestState: ChatRequestState = ChatRequestState.IDLE,
    val retainedUserMessage: String? = null,
    val retainedUserMessageId: String? = null,
    val retainedRequestText: String? = retainedUserMessage,
    val failureReason: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val orchestrator: AiMealPlannerOrchestrator,
    private val chatRepository: ChatRepository,
    private val mealDomainService: MealDomainService,
    private val mealRepository: MealRepository = UnavailableMealRepository,
    private val mealChangeRequestRepository: MealChangeRequestRepository = UnavailableMealChangeRequestRepository,
    @param:ApplicationContext private val appContext: Context? = null,
    private val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao? = null,
    private val kitInstructionDao: com.example.chatgpt_mealplanner.core.database.dao.KitInstructionDao? = null
) : ViewModel() {

    private val conversationIdFlow = MutableStateFlow("default_conversation")
    private val userMessageTextFlow = MutableStateFlow("")
    private val kitPacketRequestFlow = MutableStateFlow<String?>(null)
    /** Kits the user chose "Not now" for, so the reminder doesn't come back until the app restarts. */
    private val dismissedKitsFlow = MutableStateFlow<Set<String>>(emptySet())

    /** The first owned kit (added by scan, by hand or in bulk) that has no saved packet instructions yet. */
    private val unscannedKitFlow: Flow<String?> =
        if (inventoryDao == null || kitInstructionDao == null) flowOf(null)
        else combine(inventoryDao.getUsableItems(), kitInstructionDao.observeAll(), dismissedKitsFlow) { items, saved, dismissed ->
            items.filter { it.category == com.example.chatgpt_mealplanner.core.model.FoodCategory.KITS_MIXES }
                .firstOrNull { item ->
                    val names = listOf(
                        com.example.chatgpt_mealplanner.core.database.entity.normalizeKitName(item.displayName),
                        com.example.chatgpt_mealplanner.core.database.entity.normalizeKitName(item.canonicalName)
                    )
                    item.displayName !in dismissed &&
                        saved.none { com.example.chatgpt_mealplanner.core.database.entity.savedKitMatches(names, it.normalizedName) }
                }?.displayName
        }
    private val showPacketScanFlow = MutableStateFlow(false)
    private val packetScanKitFlow = MutableStateFlow<String?>(null)
    /** The packet sent with the message in flight, kept so a retry sends it again. */
    private var retainedPacketPhoto: com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto? = null
    private val errorMessageFlow = MutableStateFlow<String?>(null)
    private val requestStateHolderFlow = MutableStateFlow(RequestStateHolder())

    private val elapsedSecondsFlow = MutableStateFlow(0)
    private val progressStatusTextFlow = MutableStateFlow<String?>(null)
    private var progressTickerJob: Job? = null

    private val mealChangeRequestIdFlow = MutableStateFlow<String?>(null)
    private val showMealChangeRequestDialogFlow = MutableStateFlow(false)
    private val mealChangeRequestDraftFlow = MutableStateFlow("")
    private val mealChangeRequestTitleFlow = MutableStateFlow("")
    private val latestProposalFlow = MutableStateFlow<ValidatedMealProposal?>(null)
    private val latestMealPlanFlow = MutableStateFlow<List<MealProposal>?>(null)
    private val mealPlanConflictsFlow = MutableStateFlow<List<com.example.chatgpt_mealplanner.core.model.MealWithIngredients>>(emptyList())
    private val showMealPlanConflictDialogFlow = MutableStateFlow(false)
    private val latestProposedActionFlow = MutableStateFlow<AiProposedAction?>(null)
    private val isSavingProposalFlow = MutableStateFlow(false)
    private val proposalMessageIdFlow = MutableStateFlow<String?>(null)
    private val proposalJson = Json { ignoreUnknownKeys = true }

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
        cookConfirmationMealTitleFlow,
        isSavingProposalFlow,
        showMealChangeRequestDialogFlow,
        mealChangeRequestDraftFlow,
        mealChangeRequestTitleFlow,
        latestMealPlanFlow,
        mealPlanConflictsFlow,
        showMealPlanConflictDialogFlow,
        kitPacketRequestFlow,
        unscannedKitFlow,
        showPacketScanFlow,
        packetScanKitFlow
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val reqHolder = flows[4] as RequestStateHolder
        val isReqInFlight = reqHolder.requestState == ChatRequestState.IN_FLIGHT

        ChatUiState(
            activeConversationId = flows[0] as String,
            messages = (flows[1] as List<*>).map { it as ChatMessageEntity },
            latestProposal = flows[2] as ValidatedMealProposal?,
            latestMealPlan = flows[16] as List<MealProposal>?,
            mealPlanConflicts = flows[17] as List<com.example.chatgpt_mealplanner.core.model.MealWithIngredients>,
            showMealPlanConflictDialog = flows[18] as Boolean,
            latestProposedAction = flows[3] as AiProposedAction?,
            isSavingProposal = flows[12] as Boolean,
            isLoading = isReqInFlight,
            elapsedSeconds = flows[7] as Int,
            progressStatusText = flows[8] as String?,
            userMessageText = flows[5] as String,
            kitPacketRequest = (flows[19] as String?) ?: (flows[20] as String?),
            showPacketScan = flows[21] as Boolean,
            packetScanKit = flows[22] as String?,
            errorMessage = flows[6] as String?,
            requestState = reqHolder.requestState,
            retainedUserMessage = reqHolder.retainedUserMessage,
            failureReason = reqHolder.failureReason,
            showCookConfirmationDialog = flows[9] as Boolean,
            cookConfirmationMealId = flows[10] as String?,
            cookConfirmationMealTitle = flows[11] as String?,
            showMealChangeRequestDialog = flows[13] as Boolean,
            mealChangeRequestDraft = flows[14] as String,
            mealChangeRequestTitle = flows[15] as String
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )

    init {
        ensureDefaultConversationExists()
        viewModelScope.launch {
            val restoredProposal = runCatching {
                orchestrator.restorePendingMealProposal("default_conversation")
            }.getOrNull()
            if (restoredProposal != null && latestProposalFlow.value == null) {
                latestProposalFlow.value = restoredProposal
                // Reattach to a request so Save replaces the original meal instead of creating a duplicate.
                val inProgressRequests = runCatching {
                    mealChangeRequestRepository.observeAllRequests().firstOrNull().orEmpty()
                        .filter { it.status == "IN_PROGRESS" }
                }.getOrDefault(emptyList())
                if (inProgressRequests.size == 1) {
                    mealChangeRequestIdFlow.value = inProgressRequests.single().id
                }
            }
        }
        viewModelScope.launch {
            messagesFlow.collect { messages ->
                messages.asReversed().firstNotNullOfOrNull { message ->
                    message.structuredPayloadJson?.let { raw ->
                        runCatching { proposalJson.decodeFromString<ProposalMessagePayload>(raw) }.getOrNull()
                    }?.takeIf { it.state == "PENDING" && it.kind == "SINGLE_MEAL" }?.mealChangeRequestId
                }?.let { restoredRequestId ->
                    if (mealChangeRequestIdFlow.value == null) mealChangeRequestIdFlow.value = restoredRequestId
                }
                if (messages.isEmpty() && requestStateHolderFlow.value.requestState == ChatRequestState.FAILED_RETRYABLE) {
                    transitionRequestState(ChatRequestState.IDLE)
                    errorMessageFlow.value = null
                    latestProposalFlow.value = null
                    latestProposedActionFlow.value = null
                    showCookConfirmationDialogFlow.value = false
                    cookConfirmationMealIdFlow.value = null
                    cookConfirmationMealTitleFlow.value = null
                    ensureDefaultConversationExists()
                }
            }
        }
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
        latestProposalFlow.value = null
        latestMealPlanFlow.value = null
        latestProposedActionFlow.value = null
        mealChangeRequestDraftFlow.value = request.requestText
        mealChangeRequestTitleFlow.value = "this meal"
        showMealChangeRequestDialogFlow.value = false
        errorMessageFlow.value = null

        viewModelScope.launch {
            val mealTitle = runCatching {
                mealRepository.getMealWithIngredients(request.mealId)?.meal?.title
            }.getOrNull().orEmpty().ifBlank { "this meal" }
            if (mealChangeRequestIdFlow.value == request.id) {
                mealChangeRequestTitleFlow.value = mealTitle
                showMealChangeRequestDialogFlow.value = true
            }
        }
    }

    private fun buildMealChangeRequestPrompt(request: MealChangeRequestEntity, mealTitle: String, editedRequest: String): String {
        return "Prepare a replacement recipe for '$mealTitle' (meal ID ${request.mealId}) that addresses this request: $editedRequest. Preserve the existing meal's scheduled date and meal type. Return a complete recipe proposal for review, including all ingredients and complete cooking instructions. Populate changeSummary with a clear, concise explanation of how the new recipe addresses the requested change. Do not save it directly; the app will replace the existing meal only after I select Save meal."
    }

    private fun submitMealChangeRequestDraft() {
        val requestId = mealChangeRequestIdFlow.value ?: return
        val editedRequest = mealChangeRequestDraftFlow.value.trim()
        val mealTitle = mealChangeRequestTitleFlow.value.ifBlank { "this meal" }
        if (editedRequest.isBlank()) {
            errorMessageFlow.value = "Please describe the change you want."
            return
        }
        if (requestStateHolderFlow.value.requestState == ChatRequestState.IN_FLIGHT) return

        viewModelScope.launch {
            try {
                val request = mealChangeRequestRepository.observeAllRequests().firstOrNull()
                    ?.firstOrNull { it.id == requestId }
                    ?: throw IllegalStateException("This meal change request is no longer available.")
                if (request.status != "IN_PROGRESS") {
                    mealChangeRequestRepository.updateRequestStatus(requestId, "IN_PROGRESS", request.mainResponse)
                }
                val fullPrompt = buildMealChangeRequestPrompt(request, mealTitle, editedRequest)
                val displaySummary = "Change $mealTitle: $editedRequest"
                showMealChangeRequestDialogFlow.value = false
                userMessageTextFlow.value = fullPrompt
                sendMessage(displayMessageText = displaySummary)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessageFlow.value = e.message ?: "Could not start the meal adjustment."
            }
        }
    }

    private fun cancelMealChangeRequestDraft() {
        showMealChangeRequestDialogFlow.value = false
        mealChangeRequestIdFlow.value = null
        mealChangeRequestDraftFlow.value = ""
        mealChangeRequestTitleFlow.value = ""
    }

    fun onEvent(event: ChatUiEvent) {
        when (event) {
            is ChatUiEvent.UpdateMessageText -> userMessageTextFlow.value = event.text
            ChatUiEvent.SendMessage -> sendMessage()
            is ChatUiEvent.OpenPacketScan -> {
                packetScanKitFlow.value = event.kitName
                showPacketScanFlow.value = true
            }
            ChatUiEvent.ClosePacketScan -> showPacketScanFlow.value = false
            is ChatUiEvent.SendPacket -> {
                showPacketScanFlow.value = false
                sendPacket(event.packet, event.kitName)
            }
            ChatUiEvent.DismissKitPacketRequest -> {
                (kitPacketRequestFlow.value ?: uiState.value.kitPacketRequest)?.let { dismissedKitsFlow.value = dismissedKitsFlow.value + it }
                kitPacketRequestFlow.value = null
            }
            ChatUiEvent.SubmitMealChangeRequest -> submitMealChangeRequestDraft()
            is ChatUiEvent.UpdateMealChangeRequestDraft -> mealChangeRequestDraftFlow.value = event.text
            ChatUiEvent.CancelMealChangeRequestDraft -> cancelMealChangeRequestDraft()
            ChatUiEvent.RetryLastFailedMessage -> retryLastFailedMessage()
            is ChatUiEvent.SaveMealProposalToPlan -> saveMealProposalToPlan(event.proposal, event.messageId)
            is ChatUiEvent.AcceptMealPlan -> saveMealPlanToPlan(event.meals, event.messageId, event.replaceConflicts)
            ChatUiEvent.CancelMealPlanReplacement -> {
                showMealPlanConflictDialogFlow.value = false
                mealPlanConflictsFlow.value = emptyList()
            }
            is ChatUiEvent.ConfirmCookedMeal -> confirmCookedMeal(event.mealId)
            ChatUiEvent.DismissCookConfirmation -> dismissCookConfirmation()
            ChatUiEvent.ClearErrorMessage -> errorMessageFlow.value = null
        }
    }

    private fun safeLog(msg: String) {
        val event = msg.substringBefore(']').takeIf { it.startsWith("[") }?.plus("]") ?: "[CHAT_EVENT]"
        try {
            android.util.Log.i("AI_MEALPLANNER", event)
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
        reason: String? = null,
        retainedRequestText: String? = retainedMessage
    ) {
        val fromState = requestStateHolderFlow.value.requestState
        requestStateHolderFlow.value = RequestStateHolder(
            requestState = toState,
            retainedUserMessage = retainedMessage,
            retainedUserMessageId = retainedMessageId,
            retainedRequestText = retainedRequestText,
            failureReason = if (toState == ChatRequestState.FAILED_RETRYABLE) reason else null
        )
        if (toState != ChatRequestState.IN_FLIGHT) {
            stopProgressTicker()
        }
        if (reason != null) {
            safeLog("[CHAT_REQUEST_STATE] from=$fromState to=$toState reasonPresent=true")
        } else {
            safeLog("[CHAT_REQUEST_STATE] from=$fromState to=$toState")
        }
    }

    private fun sendMessage(displayMessageText: String? = null) {
        val text = userMessageTextFlow.value.trim()
        if (text.isBlank()) return
        if (requestStateHolderFlow.value.requestState == ChatRequestState.IN_FLIGHT) return
        userMessageTextFlow.value = ""
        startSend(text, displayMessageText, packet = null)
    }

    /** The packet the user chose to send, as a message of its own. */
    private fun sendPacket(packet: com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto, kitName: String?) {
        if (requestStateHolderFlow.value.requestState == ChatRequestState.IN_FLIGHT) return
        val text = if (kitName.isNullOrBlank()) "Here is the packet." else "Here are the instructions from the packet of my $kitName."
        val shown = text + if (packet.imageJpegBase64 != null) "\n(photo of the packet sent for Claude to read)" else "\n(text from the packet sent)"
        startSend(text, shown, packet)
    }

    private fun startSend(text: String, displayMessageText: String?, packet: com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto?) {
        // A proposal belongs to the previous assistant turn. Do not leave its Save card
        // visible while a new request (especially a multi-day plan) is being processed.
        latestProposalFlow.value = null
        latestMealPlanFlow.value = null
        latestProposedActionFlow.value = null
        kitPacketRequestFlow.value = null

        val visibleText = displayMessageText ?: text
        safeLog("[CHAT_SEND] messageLength=${text.length} packet=${packet != null}")

        val userMsgId = "msg_${System.currentTimeMillis()}"
        errorMessageFlow.value = null
        retainedPacketPhoto = packet
        startProgressTicker()
        transitionRequestState(ChatRequestState.IN_FLIGHT, retainedMessage = visibleText, retainedMessageId = userMsgId, retainedRequestText = text)

        viewModelScope.launch {
            safeLog("[CHAT_ORCHESTRATOR_CALL] conversationId=\"default_conversation\" messageId=\"$userMsgId\"")
            val result = processAiMessage(text, userMsgId, displayMessageText, retainedPacketPhoto)

            when (result) {
                is AiOrchestrationResult.Final -> {
                    transitionRequestState(ChatRequestState.SUCCEEDED, retainedMessage = null, retainedMessageId = null)
                    latestProposalFlow.value = result.validatedProposal
                    if (result.validatedProposal != null && mealChangeRequestIdFlow.value != null) {
                        attachMealChangeRequestToLatestProposal(mealChangeRequestIdFlow.value!!)
                    }
                    latestMealPlanFlow.value = result.mealPlan?.takeIf { it.size > 1 }
                    latestProposedActionFlow.value = result.proposedAction
                    kitPacketRequestFlow.value = result.kitPacketRequest

                    val confirmAction = result.proposedAction as? AiProposedAction.ConfirmCookedMeal
                    if (confirmAction != null) {
                        showCookConfirmationDialogFlow.value = true
                        cookConfirmationMealIdFlow.value = confirmAction.mealId
                        cookConfirmationMealTitleFlow.value = confirmAction.mealTitle
                    }
                }
                is AiOrchestrationResult.Error -> {
                    errorMessageFlow.value = result.userFacingMessage
                    transitionRequestState(ChatRequestState.FAILED_RETRYABLE, retainedMessage = visibleText, retainedMessageId = userMsgId, reason = result.userFacingMessage, retainedRequestText = text)
                }
            }
        }
    }

    /**
     * Keep the CPU awake during a user-initiated foreground AI request. This is a bounded
     * partial wake lock, not a battery-optimization exemption; Android still controls network policy.
     */
    private suspend fun processAiMessage(
        requestText: String,
        messageId: String?,
        displayMessageText: String?,
        packetPhoto: com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto? = null
    ): AiOrchestrationResult {
        val context = appContext
        val powerManager = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = if (context != null && powerManager != null) {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:AiRequest")
        } else { null }
        return try {
            // Auto-release after five minutes even if a provider call stalls unexpectedly.
            wakeLock?.acquire(5 * 60 * 1000L)
            orchestrator.processUserMessage("default_conversation", requestText, userMessageId = messageId, displayUserMessageText = displayMessageText, packetPhoto = packetPhoto)
        } finally {
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }

    private fun retryLastFailedMessage() {
        val currentHolder = requestStateHolderFlow.value
        if (currentHolder.requestState == ChatRequestState.IN_FLIGHT) return
        val text = currentHolder.retainedRequestText ?: currentHolder.retainedUserMessage ?: return
        val visibleText = currentHolder.retainedUserMessage ?: text
        val messageId = currentHolder.retainedUserMessageId

        safeLog("[CHAT_RETRY] messageId=\"$messageId\"")

        errorMessageFlow.value = null
        startProgressTicker()
        transitionRequestState(ChatRequestState.IN_FLIGHT, retainedMessage = visibleText, retainedMessageId = messageId, retainedRequestText = text)

        viewModelScope.launch {
            val result = processAiMessage(text, messageId, visibleText, retainedPacketPhoto)

            when (result) {
                is AiOrchestrationResult.Final -> {
                    transitionRequestState(ChatRequestState.SUCCEEDED, retainedMessage = null, retainedMessageId = null)
                    latestProposalFlow.value = result.validatedProposal
                    if (result.validatedProposal != null && mealChangeRequestIdFlow.value != null) {
                        attachMealChangeRequestToLatestProposal(mealChangeRequestIdFlow.value!!)
                    }
                    latestMealPlanFlow.value = result.mealPlan?.takeIf { it.size > 1 }
                    latestProposedActionFlow.value = result.proposedAction
                    kitPacketRequestFlow.value = result.kitPacketRequest

                    val confirmAction = result.proposedAction as? AiProposedAction.ConfirmCookedMeal
                    if (confirmAction != null) {
                        showCookConfirmationDialogFlow.value = true
                        cookConfirmationMealIdFlow.value = confirmAction.mealId
                        cookConfirmationMealTitleFlow.value = confirmAction.mealTitle
                    }
                }
                is AiOrchestrationResult.Error -> {
                    errorMessageFlow.value = result.userFacingMessage
                    transitionRequestState(ChatRequestState.FAILED_RETRYABLE, retainedMessage = visibleText, retainedMessageId = messageId, reason = result.userFacingMessage, retainedRequestText = text)
                }
            }
        }
    }

    private fun saveMealProposalToPlan(uiProposal: MealProposal, messageId: String? = null) {
        if (isSavingProposalFlow.value) return
        isSavingProposalFlow.value = true
        viewModelScope.launch {
            try {
                // The structured SaveMeal action is the AI's authoritative proposal. The card's
                // proposal can be stale or incomplete when a meal-change request was edited.
                val proposalToSave = MealProposalSaveResolver.resolve(
                    uiProposal = uiProposal,
                    latestValidatedProposal = latestProposalFlow.value,
                    latestProposedAction = latestProposedActionFlow.value
                )
                if (proposalToSave.ingredients.isEmpty()) {
                    errorMessageFlow.value = "Cannot save a proposal without ingredients. Please ask AI to suggest complete ingredients first."
                    return@launch
                }

                val proposal = proposalToSave
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
                val stepText = proposal.savedInstructions().ifBlank { existingMeal?.instructions }
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
                    mealDomainService.replaceMeal(existingMeal.id, meal, ingredients, preserveSchedule = true)
                } else {
                    mealDomainService.createPlannedMeal(meal, ingredients)
                }

                when (createResult) {
                    is DomainResult.Success -> {
                        updateProposalMessageAsApproved(messageId, "Saved '${meal.title}' to your meal plan.")
                        latestProposalFlow.value = null
                        runCatching { orchestrator.clearPendingMealProposal("default_conversation") }
                        if (linkedRequest != null) {
                            try {
                                mealChangeRequestRepository.updateRequestStatus(
                                    linkedRequest.id,
                                    "COMPLETED",
                                    "Completed: ${meal.title}".take(2000)
                                )
                                mealChangeRequestIdFlow.value = null
                                errorMessageFlow.value = "Updated '${meal.title}' and completed the change request."
                            } catch (e: Exception) {
                                rethrowIfCancelled()
                                errorMessageFlow.value = "The meal was updated, but the request could not be marked complete. It remains open for retry."
                            }
                        } else {
                            errorMessageFlow.value = "Saved '${meal.title}' to meal plan for ${meal.date}!"
                        }
                    }
                    is DomainResult.Failure -> {
                        errorMessageFlow.value = formatDomainError(createResult.error)
                    }
                }
            } finally {
                isSavingProposalFlow.value = false
            }
        }
    }

    private fun saveMealPlanToPlan(meals: List<MealProposal>, messageId: String? = null, replaceConflicts: Boolean = false) {
        latestMealPlanFlow.value = meals
        if (messageId != null) proposalMessageIdFlow.value = messageId
        if (isSavingProposalFlow.value) return
        if (meals.size < 2 || meals.any { it.title.isBlank() || it.ingredients.isEmpty() || it.steps.isEmpty() || it.suggestedDate.isNullOrBlank() }) {
            errorMessageFlow.value = "This meal plan is missing dates, ingredients, or cooking instructions. Ask the assistant to regenerate the complete plan."
            return
        }
        isSavingProposalFlow.value = true
        viewModelScope.launch {
            var saved = 0
            try {
                val resolvedSlots = meals.map { proposal ->
                    proposal to com.example.chatgpt_mealplanner.core.ai.resolver.MealSlotResolver.resolveSlot(
                        requestedDateStr = proposal.suggestedDate,
                        requestedMealTypeStr = proposal.mealType
                    )
                }
                val firstDate = resolvedSlots.minOf { it.second.date }
                val lastDate = resolvedSlots.maxOf { it.second.date }
                val existingMeals = mealRepository.observeMealsForWeek(firstDate, lastDate).firstOrNull().orEmpty()
                val occupiedSlots = resolvedSlots.map { it.second.date to it.second.mealType }.toSet()
                val conflicts = existingMeals.filter {
                    it.meal.status == com.example.chatgpt_mealplanner.core.model.MealStatus.PLANNED &&
                        (it.meal.date to it.meal.mealType) in occupiedSlots
                }
                if (conflicts.isNotEmpty() && !replaceConflicts) {
                    mealPlanConflictsFlow.value = conflicts
                    showMealPlanConflictDialogFlow.value = true
                    return@launch
                }
                if (replaceConflicts) {
                    val currentMeals = mealRepository.observeMealsForWeek(firstDate, lastDate).firstOrNull().orEmpty()
                    val currentConflicts = currentMeals.filter {
                        it.meal.status == com.example.chatgpt_mealplanner.core.model.MealStatus.PLANNED &&
                            (it.meal.date to it.meal.mealType) in occupiedSlots
                    }
                    // A replace action is only valid after the user has seen the confirmation
                    // for these exact existing meals. If the slots changed since the dialog was
                    // shown (or the action was invoked without the dialog), show confirmation first.
                    val presentedConflictIds = mealPlanConflictsFlow.value.map { it.meal.id }.toSet()
                    val currentConflictIds = currentConflicts.map { it.meal.id }.toSet()
                    if (currentConflicts.isNotEmpty() &&
                        (!showMealPlanConflictDialogFlow.value || !presentedConflictIds.containsAll(currentConflictIds))) {
                        mealPlanConflictsFlow.value = currentConflicts
                        showMealPlanConflictDialogFlow.value = true
                        return@launch
                    }
                    for (conflict in currentConflicts) {
                        when (val deletion = mealDomainService.deletePlannedMeal(conflict.meal.id)) {
                            is DomainResult.Success -> Unit
                            is DomainResult.Failure -> {
                                errorMessageFlow.value = "Couldn't replace '${conflict.meal.title}': ${formatDomainError(deletion.error)}. No new meals were added."
                                return@launch
                            }
                        }
                    }
                    showMealPlanConflictDialogFlow.value = false
                    mealPlanConflictsFlow.value = emptyList()
                }
                for ((proposal, slot) in resolvedSlots) {
                    val meal = Meal(
                        id = UUID.randomUUID().toString(),
                        date = slot.date,
                        mealType = slot.mealType,
                        title = proposal.title,
                        servings = proposal.servings,
                        batchPortions = proposal.batchPortions,
                        immediatePortions = proposal.immediatePortions,
                        leftoverPortions = proposal.leftoverPortions,
                        cookInTwoBatches = proposal.cookInTwoBatches,
                        tags = proposal.tags.mapNotNull { runCatching { MealTag.valueOf(it.uppercase()) }.getOrNull() }.toSet(),
                        instructions = proposal.savedInstructions()
                    )
                    val ingredients = proposal.ingredients.map { ingredient ->
                        MealIngredient(
                            id = UUID.randomUUID().toString(),
                            mealId = meal.id,
                            canonicalIngredientId = ingredient.canonicalIngredientId,
                            displayName = ingredient.displayName,
                            plannedQuantity = ingredient.quantity,
                            plannedUnit = com.example.chatgpt_mealplanner.core.model.parseQuantityUnit(ingredient.unit),
                            optional = ingredient.optional,
                            substitutionAllowed = ingredient.substitutionAllowed
                        )
                    }
                    when (val result = mealDomainService.createPlannedMeal(meal, ingredients)) {
                        is DomainResult.Success -> saved++
                        is DomainResult.Failure -> {
                            errorMessageFlow.value = if (saved == 0) {
                                "Couldn't add '${proposal.title}' to the meal plan: ${formatDomainError(result.error)}"
                            } else {
                                "Added $saved of ${meals.size} meals before '${proposal.title}' failed: ${formatDomainError(result.error)}"
                            }
                            return@launch
                        }
                    }
                }
                latestMealPlanFlow.value = null
                showMealPlanConflictDialogFlow.value = false
                mealPlanConflictsFlow.value = emptyList()
                updateProposalMessageAsApproved(proposalMessageIdFlow.value, "Approved and added all $saved meals to your planner. Shopping requirements update automatically.")
                proposalMessageIdFlow.value = null
                errorMessageFlow.value = "Accepted meal plan: added all $saved meals to your planner. Shopping requirements update automatically."
            } finally {
                isSavingProposalFlow.value = false
            }
        }
    }


    private suspend fun attachMealChangeRequestToLatestProposal(requestId: String) {
        runCatching {
            val latest = chatRepository.getMessagesForConversation("default_conversation")
                .lastOrNull { it.role.equals("ASSISTANT", ignoreCase = true) && !it.structuredPayloadJson.isNullOrBlank() }
                ?: return@runCatching
            val payload = proposalJson.decodeFromString<ProposalMessagePayload>(latest.structuredPayloadJson!!)
            if (payload.kind == "SINGLE_MEAL" && payload.state == "PENDING") {
                chatRepository.saveMessage(
                    latest.copy(
                        structuredPayloadJson = proposalJson.encodeToString(
                            payload.copy(mealChangeRequestId = requestId)
                        )
                    )
                )
            }
        }
    }

    private suspend fun updateProposalMessageAsApproved(messageId: String?, approvedText: String) {
        if (messageId.isNullOrBlank()) return
        runCatching {
            val existing = chatRepository.getMessagesForConversation("default_conversation")
                .firstOrNull { it.id == messageId } ?: return@runCatching
            val payload = existing.structuredPayloadJson?.let {
                proposalJson.decodeFromString<ProposalMessagePayload>(it)
            } ?: return@runCatching
            chatRepository.saveMessage(
                existing.copy(
                    content = approvedText,
                    structuredPayloadJson = proposalJson.encodeToString(payload.copy(state = "APPROVED"))
                )
            )
        }.onFailure {
            errorMessageFlow.value = "The meal was saved, but its chat approval status could not be updated. Reopen the conversation and check the planner."
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
