package com.example.chatgpt_mealplanner.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.feature.chat.ChatUiEvent
import com.example.chatgpt_mealplanner.feature.chat.ChatUiState
import com.example.chatgpt_mealplanner.contract.ProposalMessagePayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

fun formatModelDisplayName(modelId: String?): String? {
    if (modelId.isNullOrBlank()) return null
    return when (modelId) {
        "claude-haiku-5-5" -> "Claude Haiku 5.5"
        "claude-sonnet-5-5" -> "Claude Sonnet 5.5"
        else -> modelId
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues = PaddingValues(0.dp)
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val totalItemCount = uiState.messages.size + (if (uiState.isLoading) 1 else 0)
    val targetIndex = (totalItemCount - 1).coerceAtLeast(0)

    var hasScrolledToInitialBottom by remember(uiState.activeConversationId) { mutableStateOf(false) }

    val isNearBottom by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleIndex >= targetIndex - 2
        }
    }

    val isImeVisible = WindowInsets.isImeVisible
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars

    val imeBottomPx = imeInsets.getBottom(density)
    val navBottomPx = navInsets.getBottom(density)

    var composerTopPx by remember { mutableIntStateOf(0) }
    var composerBottomPx by remember { mutableIntStateOf(0) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    var rootHeightPx by remember { mutableIntStateOf(0) }

    val topPadding = innerPadding.calculateTopPadding()
    val imeBottomDp = with(density) { imeBottomPx.toDp() }
    val bottomPadding = maxOf(innerPadding.calculateBottomPadding(), imeBottomDp)

    LaunchedEffect(isImeVisible, imeBottomPx, navBottomPx, composerBottomPx, rootHeightPx) {
        android.util.Log.d(
            "IME_DEBUG",
            "[IME_DEBUG] visible=$isImeVisible imeBottomPx=$imeBottomPx navBottomPx=$navBottomPx bottomPaddingDp=$bottomPadding composerTopPx=$composerTopPx composerBottomPx=$composerBottomPx composerHeightPx=$composerHeightPx rootHeightPx=$rootHeightPx"
        )
    }

    // 1. Initial screen entry or conversation switch: scroll to latest message at the bottom immediately
    LaunchedEffect(uiState.activeConversationId, uiState.messages.size) {
        if (!hasScrolledToInitialBottom && totalItemCount > 0) {
            listState.scrollToItem(targetIndex)
            hasScrolledToInitialBottom = true
        } else if (hasScrolledToInitialBottom && (isNearBottom || uiState.isLoading)) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    // 2. Scroll to bottom when software keyboard opens
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && totalItemCount > 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onEvent(ChatUiEvent.ClearErrorMessage)
        }
    }

    val canSubmit = !uiState.isLoading && uiState.userMessageText.isNotBlank()
    val submitMessage: () -> Unit = {
        if (canSubmit) {
            onEvent(ChatUiEvent.SendMessage)
        }
    }

    if (uiState.showPacketScan) {
        PacketScanDialog(
            kitName = uiState.packetScanKit,
            onSend = { onEvent(ChatUiEvent.SendPacket(it, uiState.packetScanKit)) },
            onCancel = { onEvent(ChatUiEvent.ClosePacketScan) }
        )
    }

    if (uiState.showMealChangeRequestDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.CancelMealChangeRequestDraft) },
            title = { Text("What change would you like to make to ${uiState.mealChangeRequestTitle}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Edit the request before sending it to the AI assistant.")
                    OutlinedTextField(
                        value = uiState.mealChangeRequestDraft,
                        onValueChange = { onEvent(ChatUiEvent.UpdateMealChangeRequestDraft(it)) },
                        label = { Text("Requested change") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onEvent(ChatUiEvent.SubmitMealChangeRequest) },
                    enabled = uiState.mealChangeRequestDraft.isNotBlank() && !uiState.isLoading
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(ChatUiEvent.CancelMealChangeRequestDraft) }) { Text("Cancel") }
            }
        )
    }

    if (uiState.showMealPlanConflictDialog) {
        val conflicts = uiState.mealPlanConflicts
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.CancelMealPlanReplacement) },
            title = { Text("Replace existing planned meals?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("These meals already occupy slots in the proposed plan. Accepting will delete them and replace them with the new meals:")
                    conflicts.forEach { conflict ->
                        Text(
                            text = "• ${conflict.meal.date} — ${conflict.meal.mealType}: ${conflict.meal.title}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text("Meals in other dates or meal slots will be left unchanged.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val plan = uiState.latestMealPlan
                        if (plan != null) onEvent(ChatUiEvent.AcceptMealPlan(plan, replaceConflicts = true))
                    },
                    enabled = uiState.latestMealPlan != null && !uiState.isSavingProposal
                ) { Text("Replace and accept plan") }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(ChatUiEvent.CancelMealPlanReplacement) }) { Text("Keep existing meals") }
            }
        )
    }

    if (uiState.showCookConfirmationDialog && uiState.cookConfirmationMealId != null) {
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.DismissCookConfirmation) },
            title = { Text("Confirm Meal Cooking") },
            text = {
                Text("Confirm cooking '${uiState.cookConfirmationMealTitle ?: "this meal"}' and deduct ingredients from inventory?")
            },
            confirmButton = {
                Button(
                    onClick = { onEvent(ChatUiEvent.ConfirmCookedMeal(uiState.cookConfirmationMealId)) }
                ) {
                    Text("Confirm Cooked")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(ChatUiEvent.DismissCookConfirmation) }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topPadding, bottom = bottomPadding)
            .onGloballyPositioned { coordinates ->
                rootHeightPx = coordinates.size.height
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.messages, key = { it.id }) { msg ->
                    val proposalPayload = remember(msg.structuredPayloadJson) {
                        msg.structuredPayloadJson?.let { raw ->
                            runCatching { Json.decodeFromString<ProposalMessagePayload>(raw) }.getOrNull()
                        }
                    }
                    val isPendingProposal = proposalPayload?.state == "PENDING"
                    if (isPendingProposal && proposalPayload != null) {
                        when (proposalPayload.kind) {
                            "MEAL_PLAN" -> MealPlanProposalCard(
                                validatedMeals = proposalPayload.meals,
                                onAccept = { onEvent(ChatUiEvent.AcceptMealPlan(it, messageId = msg.id)) },
                                isSaving = uiState.isSavingProposal
                            )
                            "SINGLE_MEAL" -> proposalPayload.singleMeal?.let { validated ->
                                MealProposalCard(
                                    validatedProposal = validated,
                                    onSaveToPlan = { onEvent(ChatUiEvent.SaveMealProposalToPlan(it, messageId = msg.id)) },
                                    isSaving = uiState.isSavingProposal
                                )
                            }
                        }
                    } else if (msg.content.isNotBlank()) {
                    val isUser = msg.role.equals("USER", ignoreCase = true)
                    val modelDisplayName = formatModelDisplayName(msg.modelId)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isUser) "You" else "Pantry Assistant",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!isUser && modelDisplayName != null) {
                                        Text(
                                            text = modelDisplayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                if (isUser) {
                                    Text(
                                        text = msg.content,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                } else {
                                    AssistantMarkdownText(
                                        markdown = msg.content
                                    )
                                }
                            }
                        }
                    }
                    }
                }

                if (uiState.isLoading) {
                    item(key = "loading_indicator") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                            val statusText = uiState.progressStatusText ?: "Processing AI request..."
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (uiState.isRetryAvailable && uiState.retainedUserMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Couldn't complete that request.",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = uiState.retainedUserMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                            uiState.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onEvent(ChatUiEvent.RetryLastFailedMessage) },
                            enabled = !uiState.isLoading && uiState.isRetryAvailable
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            uiState.kitPacketRequest?.let { kit ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Instructions needed for $kit", style = MaterialTheme.typography.titleSmall)
                        Text("Scan the packet, or paste its instructions into the message box.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEvent(ChatUiEvent.OpenPacketScan(kit)) }, modifier = Modifier.testTag("scan-packet-button")) { Text("Scan packet") }
                            TextButton(onClick = { onEvent(ChatUiEvent.DismissKitPacketRequest) }) { Text("Not now") }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .onGloballyPositioned { coordinates ->
                        composerTopPx = coordinates.positionInRoot().y.toInt()
                        composerHeightPx = coordinates.size.height
                        composerBottomPx = composerTopPx + composerHeightPx
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.userMessageText,
                    onValueChange = { onEvent(ChatUiEvent.UpdateMessageText(it)) },
                    placeholder = { Text("Ask about meals or stock", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { submitMessage() }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
                                if (!keyEvent.isShiftPressed) {
                                    submitMessage()
                                    true
                                } else false
                            } else false
                        }
                )
                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = submitMessage,
                    enabled = canSubmit
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Message")
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
