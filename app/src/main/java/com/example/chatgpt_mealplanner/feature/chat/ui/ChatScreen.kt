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
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.feature.chat.ChatUiEvent
import com.example.chatgpt_mealplanner.feature.chat.ChatUiState

fun formatModelDisplayName(modelId: String?): String? {
    if (modelId.isNullOrBlank()) return null
    return when (modelId) {
        "gemini-3.7-flash" -> "Gemini 3.7 Flash"
        "gemini-3.6-flash" -> "Gemini 3.6 Flash"
        "gemini-3.5-flash-lite" -> "Gemini 3.5 Flash Lite"
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

    val totalItemCount = uiState.messages.size + (if (uiState.latestProposal != null) 1 else 0) + (if (uiState.isLoading) 1 else 0)
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

                if (uiState.isLoading) {
                    item(key = "loading_indicator") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
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
                    placeholder = { Text("Ask about meals, inventory, or shopping...") },
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
                    Icon(Icons.Default.Send, contentDescription = "Send Message")
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
