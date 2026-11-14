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
import kotlinx.coroutines.flow.first
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
    private val kitInstructionDao: com.example.chatgpt_mealplanner.core.database.dao.KitInstructionDao? = null,
    private val usageQuota: com.example.chatgpt_mealplanner.core.quota.UsageQuota? = null
) : ViewModel() {

    /** What is left of today's allowance, for the line under the message box. */
    val usage: kotlinx.coroutines.flow.StateFlow<com.example.chatgpt_mealplanner.core.quota.UsageSnapshot> =
        usageQuota?.snapshot ?: MutableStateFlow(com.example.chatgpt_mealplanner.core.quota.UsageSnapshot(unlimited = true))

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
    private val packetScanRecipeFlow = MutableStateFlow(false)
    private val STOP_PLAN = "Stop the plan"
    private val quickRepliesFlow = MutableStateFlow<List<String>>(emptyList())
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
    /** Meals Claude still has to propose in the plan being built one meal at a time. */
    /** The meals of the plan the user asked for that are still to be proposed. The app counts them, not Claude. */
    private val planQueue = ArrayDeque<com.example.chatgpt_mealplanner.core.util.PlanSlot>()
    private var planTotal = 0
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
        kitPacketRequestFlow,
        unscannedKitFlow,
        showPacketScanFlow,
        packetScanKitFlow,
        packetScanRecipeFlow,
        quickRepliesFlow
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val reqHolder = flows[4] as RequestStateHolder
        val isReqInFlight = reqHolder.requestState == ChatRequestState.IN_FLIGHT

        ChatUiState(
            activeConversationId = flows[0] as String,
            messages = (flows[1] as List<*>).map { it as ChatMessageEntity },
            latestProposal = flows[2] as ValidatedMealProposal?,
            leftoverNote = previewLeftovers((flows[2] as ValidatedMealProposal?)?.proposal),
            latestProposedAction = flows[3] as AiProposedAction?,
            isSavingProposal = flows[12] as Boolean,
            isLoading = isReqInFlight,
            elapsedSeconds = flows[7] as Int,
            progressStatusText = flows[8] as String?,
            userMessageText = flows[5] as String,
            kitPacketRequest = (flows[16] as String?) ?: (flows[17] as String?),
            showPacketScan = flows[18] as Boolean,
            packetScanKit = flows[19] as String?,
            packetScanRecipe = flows[20] as Boolean,
            quickReplies = flows[21] as List<String>,
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
                if (messages.isEmpty()) planQueue.clear()
                messages.asReversed().firstNotNullOfOrNull { message ->
                    message.structuredPayloadJson?.let { raw ->
                        runCatching { proposalJson.decodeFromString<ProposalMessagePayload>(raw) }.getOrNull()
                    }?.takeIf { it.state == "PENDING" && it.kind == "SINGLE_MEAL" }?.mealChangeRequestId
                }?.let { restoredRequestId ->
                    if (mealChangeRequestIdFlow.value == null) mealChangeRequestIdFlow.value = restoredRequestId
                }
                offerRetryIfCutOff(messages)
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

    /**
     * A user's message with no reply after it means the request was cut off (an update install, a
     * crash, the phone killing the app). Offer Retry, as for any other failed request.
     */
    private fun offerRetryIfCutOff(messages: List<ChatMessageEntity>) {
        val last = messages.lastOrNull() ?: return
        if (!last.role.equals("USER", ignoreCase = true)) return
        val state = requestStateHolderFlow.value.requestState
        if (state == ChatRequestState.IN_FLIGHT || state == ChatRequestState.FAILED_RETRYABLE) return
        // The photo of a packet or recipe is not kept, so there is nothing to send again.
        if (last.content.contains("sent for Claude to read)")) return
        val text = if (last.content.startsWith("Next meal (")) "Please propose the next meal of my plan. ${last.content}" else last.content
        if (text.isBlank()) return
        transitionRequestState(
            ChatRequestState.FAILED_RETRYABLE,
            retainedMessage = last.content,
            retainedMessageId = last.id,
            reason = "Claude's reply was interrupted. Tap Retry to ask again.",
            retainedRequestText = text
        )
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
                packetScanRecipeFlow.value = false
                showPacketScanFlow.value = true
            }
            is ChatUiEvent.OpenRecipeScan -> {
                packetScanKitFlow.value = event.title
                packetScanRecipeFlow.value = true
                showPacketScanFlow.value = true
            }
            is ChatUiEvent.SendText -> {
                userMessageTextFlow.value = event.text
                sendMessage()
            }
            ChatUiEvent.ClosePacketScan -> showPacketScanFlow.value = false
            is ChatUiEvent.SendPacket -> {
                showPacketScanFlow.value = false
                sendPacket(event.packet, event.kitName, event.recipe)
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
        if (text != STOP_PLAN && usageQuota != null &&
            !usageQuota.canUse(com.example.chatgpt_mealplanner.core.quota.QuotaKind.CHAT_MESSAGE)
        ) {
            errorMessageFlow.value = usageQuota.limitMessage(com.example.chatgpt_mealplanner.core.quota.QuotaKind.CHAT_MESSAGE)
            return
        }
        userMessageTextFlow.value = ""
        if (text == STOP_PLAN && planQueue.isNotEmpty()) {
            planQueue.clear()
            errorMessageFlow.value = "Plan stopped. Meals you already saved stay in your planner."
            quickRepliesFlow.value = emptyList()
            return
        }
        val slots = com.example.chatgpt_mealplanner.core.util.PlanRequestParser.parse(text)
        if (slots != null) {
            // The app counts the meals and asks for them one at a time.
            planQueue.clear()
            planQueue.addAll(slots)
            planTotal = slots.size
            startSend(planSlotPrompt(planQueue.removeFirst(), text), displayMessageText ?: text, packet = null)
            return
        }
        startSend(text, displayMessageText, packet = null)
    }

    private fun planSlotPrompt(slot: com.example.chatgpt_mealplanner.core.util.PlanSlot, request: String?): String {
        val number = planTotal - planQueue.size
        val day = slot.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))
        val meal = slot.mealType.name.lowercase()
        return buildString {
            if (request != null) append("The user asked: \"$request\"\n")
            append("The app is building this meal plan one meal at a time. Propose ONLY meal $number of $planTotal now: $day $meal ")
            append("(date ${slot.date}, mealType ${slot.mealType.name}). Offer it as one normal recipe card with that date and mealType. ")
            append("Do not write the other meals or a list of the plan. Keep it different from meals already in upcomingPlannedMeals and do not use up the same stock twice.")
        }
    }

    /** The packet the user chose to send, as a message of its own. */
    private fun sendPacket(packet: com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto, kitName: String?, recipe: Boolean = false) {
        if (requestStateHolderFlow.value.requestState == ChatRequestState.IN_FLIGHT) return
        val text = when {
            recipe && kitName.isNullOrBlank() -> "Here is a recipe. Please read it and save it to my recipes."
            recipe -> "Here is my \"$kitName\" recipe again. Please read it and save it, replacing the one you have."
            kitName.isNullOrBlank() -> "Here is the packet."
            else -> "Here are the instructions from the packet of my $kitName."
        }
        val what = if (recipe) "recipe" else "packet"
        val pages = packet.imagesJpegBase64.size
        val shown = text + if (pages == 1) "\n(photo of the $what sent for Claude to read)" else "\n($pages photos of the $what sent for Claude to read)"
        startSend(text, shown, packet)
    }

    /** The assistant's own suggestions; for a plan or a recipe card, options for changing it. */
    private fun quickRepliesFor(result: com.example.chatgpt_mealplanner.contract.AiOrchestrationResult.Final): List<String> = when {
        result.validatedProposal != null -> listOf("Make it healthier", "Make it quicker", "Make it spicier", "Swap an ingredient") +
            (result.validatedProposal?.proposal?.takeIf { it.batchPortions > it.servings }?.let { listOf("Freeze the extra portions") } ?: emptyList()) +
            (if (planQueue.isNotEmpty()) listOf(STOP_PLAN) else emptyList())
        else -> result.quickReplies
    }

    private fun startSend(text: String, displayMessageText: String?, packet: com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto?) {
        // Every request to Claude (typed messages, photos of packets and recipes, recipe links, each meal of a plan) is one message.
        if (usageQuota != null) {
            if (!usageQuota.canUse(com.example.chatgpt_mealplanner.core.quota.QuotaKind.CHAT_MESSAGE)) {
                planQueue.clear()
                errorMessageFlow.value = usageQuota.limitMessage(com.example.chatgpt_mealplanner.core.quota.QuotaKind.CHAT_MESSAGE)
                return
            }
            usageQuota.record(com.example.chatgpt_mealplanner.core.quota.QuotaKind.CHAT_MESSAGE)
        }
        // A proposal belongs to the previous assistant turn. Do not leave its Save card
        // visible while a new request (especially a multi-day plan) is being processed.
        latestProposalFlow.value = null
        latestProposedActionFlow.value = null
        kitPacketRequestFlow.value = null
        quickRepliesFlow.value = emptyList()

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
                    latestProposedActionFlow.value = result.proposedAction
                    kitPacketRequestFlow.value = result.kitPacketRequest
                    quickRepliesFlow.value = quickRepliesFor(result)

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
        var text = currentHolder.retainedRequestText ?: currentHolder.retainedUserMessage ?: return
        // A plan request that was cut off before its first meal starts the plan again.
        com.example.chatgpt_mealplanner.core.util.PlanRequestParser.parse(text)?.let { slots ->
            planQueue.clear()
            planQueue.addAll(slots)
            planTotal = slots.size
            text = planSlotPrompt(planQueue.removeFirst(), text)
        }
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
                    latestProposedActionFlow.value = result.proposedAction
                    kitPacketRequestFlow.value = result.kitPacketRequest
                    quickRepliesFlow.value = quickRepliesFor(result)

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
                // Portions beyond what the household eats now fill later days of the requested plan, or are frozen.
                val extraPortions = (proposal.batchPortions - proposal.servings).coerceAtLeast(0)
                // Replacing an uncooked meal frees the leftover days planned for the old one: they are reused first.
                val oldLeftoverDays = existingMeal?.let { mealDomainService.leftoverDaysFor(it.id) }.orEmpty()
                val mealDate = existingMeal?.date ?: resolvedSlot.date
                val placement = leftoverPlacement(
                    proposal, java.time.LocalDate.parse(mealDate),
                    oldLeftoverDays.mapNotNull { runCatching { com.example.chatgpt_mealplanner.core.util.PlanSlot(java.time.LocalDate.parse(it.date), it.mealType) }.getOrNull() }
                )
                val batchPortions = proposal.servings + extraPortions
                val immediatePortions = proposal.servings * (1 + placement.slots.size)
                val leftoverTags = if (extraPortions > 0) parsedTags + MealTag.BATCH_COOK else parsedTags
                val meal = if (existingMeal != null) {
                    existingMeal.copy(
                        title = proposal.title,
                        servings = proposal.servings,
                        batchPortions = batchPortions,
                        immediatePortions = immediatePortions,
                        leftoverPortions = placement.frozenPortions,
                        cookInTwoBatches = proposal.cookInTwoBatches,
                        tags = leftoverTags,
                        instructions = stepText,
                        updatedAt = System.currentTimeMillis()
                    )
                } else Meal(
                    id = UUID.randomUUID().toString(),
                    date = resolvedSlot.date,
                    mealType = resolvedSlot.mealType,
                    title = proposal.title,
                    servings = proposal.servings,
                    batchPortions = batchPortions,
                    immediatePortions = immediatePortions,
                    leftoverPortions = placement.frozenPortions,
                    cookInTwoBatches = proposal.cookInTwoBatches,
                    tags = leftoverTags,
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
                        oldLeftoverDays.forEach { mealDomainService.deletePlannedMeal(it.id) }
                        val leftoverNote = planLeftoverDays(meal, placement)
                        continuePlanAfterSave(meal.title)
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
                            errorMessageFlow.value = "Saved '${meal.title}' to meal plan for ${meal.date}!" + leftoverNote
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

    /**
     * Where a batch's extra portions go: the days the user named, else the next dinners of the plan
     * they asked for (plus any days freed by the meal being replaced). Nothing fits means freezing.
     */
    private fun leftoverPlacement(
        proposal: MealProposal,
        mealDate: java.time.LocalDate,
        freedSlots: List<com.example.chatgpt_mealplanner.core.util.PlanSlot> = emptyList()
    ): com.example.chatgpt_mealplanner.core.util.LeftoverPlacement {
        val extras = (proposal.batchPortions - proposal.servings).coerceAtLeast(0)
        if (proposal.extrasPlan.equals("FREEZE", ignoreCase = true)) {
            return com.example.chatgpt_mealplanner.core.util.LeftoverPlacement(emptyList(), extras)
        }
        val named = proposal.extraDates.mapNotNull { runCatching { java.time.LocalDate.parse(it.trim()) }.getOrNull() }
            .map { com.example.chatgpt_mealplanner.core.util.PlanSlot(it, com.example.chatgpt_mealplanner.core.model.MealType.DINNER) }
        val candidates = named.ifEmpty {
            freedSlots + planQueue.filter { it.mealType == com.example.chatgpt_mealplanner.core.model.MealType.DINNER }
        }
        return com.example.chatgpt_mealplanner.core.util.LeftoverPlacer.place(extras, proposal.servings, mealDate, null, candidates)
    }

    /** What the recipe card tells the user about the extra portions, before they save. */
    private fun previewLeftovers(proposal: MealProposal?): String? {
        if (proposal == null || proposal.batchPortions <= proposal.servings) return null
        val extras = proposal.batchPortions - proposal.servings
        val date = runCatching {
            java.time.LocalDate.parse(com.example.chatgpt_mealplanner.core.ai.resolver.MealSlotResolver.resolveSlot(proposal.suggestedDate, proposal.mealType).date)
        }.getOrNull() ?: return null
        val placement = leftoverPlacement(proposal, date)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault())
        val where = buildList {
            if (placement.slots.isNotEmpty()) add("saved against " + placement.slots.joinToString(", ") { "${it.date.format(fmt)} dinner" })
            if (placement.frozenPortions > 0) add("${placement.frozenPortions} portion${if (placement.frozenPortions == 1) "" else "s"} frozen")
        }.joinToString(" and ")
        return "$extras extra portion${if (extras == 1) "" else "s"} will be $where. " +
            "You can ask Claude to handle them differently, such as freezing them, using other days, or cooking less."
    }

    /**
     * The extra portions of a batch go on later days as planned "Leftovers" meals, so the planner and
     * future requests show those days as taken. Returns a sentence for the user.
     */
    private suspend fun planLeftoverDays(
        meal: Meal,
        placement: com.example.chatgpt_mealplanner.core.util.LeftoverPlacement
    ): String {
        val days = mutableListOf<String>()
        placement.slots.forEach { slot ->
            val entry = Meal(
                id = UUID.randomUUID().toString(),
                date = slot.date.toString(),
                mealType = slot.mealType,
                title = "Leftovers: ${meal.title}",
                servings = meal.servings,
                batchPortions = meal.servings,
                immediatePortions = meal.servings,
                leftoverPortions = 0,
                cookInTwoBatches = false,
                tags = setOf(MealTag.BATCH_COOK),
                instructions = "Leftovers from ${meal.title}. Reheat until piping hot.",
                notes = com.example.chatgpt_mealplanner.core.util.LeftoverMeals.noteFor(meal.id)
            )
            if (mealDomainService.createPlannedMeal(entry, emptyList()) is DomainResult.Success) {
                days += slot.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))
                planQueue.remove(slot)
                planTotal--
            }
        }
        return buildList {
            if (days.isNotEmpty()) add(" Leftovers planned for ${days.joinToString(", ")}.")
            if (placement.frozenPortions > 0) add(" ${placement.frozenPortions} extra portion${if (placement.frozenPortions == 1) "" else "s"} to freeze.")
        }.joinToString("")
    }

    /** A plan is built one meal at a time: once a meal is saved, the app asks for the next slot of the plan. */
    private fun continuePlanAfterSave(savedTitle: String) {
        if (planQueue.isEmpty()) return
        val slot = planQueue.removeFirst()
        val number = planTotal - planQueue.size
        val day = slot.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))
        viewModelScope.launch {
            // The save finishes before the next request starts.
            isSavingProposalFlow.first { !it }
            startSend(
                "Saved \"$savedTitle\". " + planSlotPrompt(slot, null),
                "Next meal ($number of $planTotal): $day ${slot.mealType.name.lowercase()}",
                null
            )
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
