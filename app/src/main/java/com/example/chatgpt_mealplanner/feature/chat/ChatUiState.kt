package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.contract.AiProposedAction
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ValidatedMealProposal
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients

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
    /** What the latest recipe card says about its extra portions, such as which days they will be saved against. */
    val leftoverNote: String? = null,
    val latestProposedAction: AiProposedAction? = null,
    val isSavingProposal: Boolean = false,
    val isLoading: Boolean = false,
    val elapsedSeconds: Int = 0,
    val progressStatusText: String? = null,
    val userMessageText: String = "",
    /** A kit the assistant asked for the packet of: the chat shows a Scan packet button for it. */
    val kitPacketRequest: String? = null,
    /** Short answers offered as buttons under the last assistant message. */
    val quickReplies: List<String> = emptyList(),
    /** Whether the Scan packet screen is open, and for which kit (null when the user opened it themselves). */
    val showPacketScan: Boolean = false,
    val packetScanKit: String? = null,
    /** Whether the scan screen is for a recipe (a book page or card) rather than a kit's packet. */
    val packetScanRecipe: Boolean = false,
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
    /** Opens the Scan packet screen, for the kit the assistant asked about or none. */
    data class OpenPacketScan(val kitName: String?) : ChatUiEvent
    /** Opens the scan screen for a recipe; [title] is set when replacing a saved recipe. */
    data class OpenRecipeScan(val title: String?) : ChatUiEvent
    /** Sends a ready-made message as if typed, such as a recipe link. */
    data class SendText(val text: String) : ChatUiEvent
    object ClosePacketScan : ChatUiEvent
    /** Sends the packet as the user chose: the text they checked, or the picture for Claude to read. */
    data class SendPacket(val packet: com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto, val kitName: String?, val recipe: Boolean = false) : ChatUiEvent
    object DismissKitPacketRequest : ChatUiEvent
    object SubmitMealChangeRequest : ChatUiEvent
    data class UpdateMealChangeRequestDraft(val text: String) : ChatUiEvent
    object CancelMealChangeRequestDraft : ChatUiEvent
    object RetryLastFailedMessage : ChatUiEvent
    data class SaveMealProposalToPlan(val proposal: MealProposal, val messageId: String? = null) : ChatUiEvent
    data class ConfirmCookedMeal(val mealId: String) : ChatUiEvent
    object DismissCookConfirmation : ChatUiEvent
    object ClearErrorMessage : ChatUiEvent
}
