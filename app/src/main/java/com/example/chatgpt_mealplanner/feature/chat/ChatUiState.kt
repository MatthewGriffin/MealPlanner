package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.contract.AiProposedAction
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ValidatedMealProposal
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity

enum class ChatRequestState {
    IDLE,
    IN_FLIGHT,
    FAILED_RETRYABLE,
    SUCCEEDED
}

data class ChatUiState(
    val activeConversationId: String = "default_conversation",
    val messages: List<ChatMessageEntity> = emptyList(),
    val latestProposal: ValidatedMealProposal? = null,
    val latestMealPlan: List<MealProposal>? = null,
    val latestProposedAction: AiProposedAction? = null,
    val isSavingProposal: Boolean = false,
    val isLoading: Boolean = false,
    val elapsedSeconds: Int = 0,
    val progressStatusText: String? = null,
    val userMessageText: String = "",
    val errorMessage: String? = null,
    val requestState: ChatRequestState = ChatRequestState.IDLE,
    val retainedUserMessage: String? = null,
    val failureReason: String? = null,
    val showCookConfirmationDialog: Boolean = false,
    val cookConfirmationMealId: String? = null,
    val cookConfirmationMealTitle: String? = null,
    val showMealChangeRequestDialog: Boolean = false,
    val mealChangeRequestDraft: String = "",
    val mealChangeRequestTitle: String = "this meal"
) {
    val failedUserMessage: String?
        get() = if (requestState == ChatRequestState.FAILED_RETRYABLE) retainedUserMessage else null

    val isRetryAvailable: Boolean
        get() = requestState == ChatRequestState.FAILED_RETRYABLE && !retainedUserMessage.isNullOrBlank()
}

sealed interface ChatUiEvent {
    data class UpdateMessageText(val text: String) : ChatUiEvent
    object SendMessage : ChatUiEvent
    object SubmitMealChangeRequest : ChatUiEvent
    data class UpdateMealChangeRequestDraft(val text: String) : ChatUiEvent
    object CancelMealChangeRequestDraft : ChatUiEvent
    object RetryLastFailedMessage : ChatUiEvent
    data class SaveMealProposalToPlan(val proposal: MealProposal) : ChatUiEvent
    data class AcceptMealPlan(val meals: List<MealProposal>) : ChatUiEvent
    data class ConfirmCookedMeal(val mealId: String) : ChatUiEvent
    object DismissCookConfirmation : ChatUiEvent
    object ClearErrorMessage : ChatUiEvent
}
