package com.example.chatgpt_mealplanner.feature.mealplanner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity

/** Keep request and response editors visible as the keyboard changes the viewport. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MealRequestTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboardBottom) {
        if (focused) requester.bringIntoView()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        maxLines = 4,
        modifier = Modifier.fillMaxWidth()
            .bringIntoViewRequester(requester)
            .onFocusChanged { focused = it.isFocused }
    )
}
