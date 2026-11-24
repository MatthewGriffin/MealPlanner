package com.example.chatgpt_mealplanner.feature.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.util.formatQuantityAndUnit

/** A search box over the pantry: type a few letters and tap the item that is the same thing. */
@Composable
fun PantryItemPickerDialog(
    title: String,
    items: List<InventoryItem>,
    onPick: (InventoryItem) -> Unit,
    onDismiss: () -> Unit
) {
    val query = remember { mutableStateOf("") }
    val shown = remember(items, query.value) {
        val q = query.value.trim().lowercase()
        items.filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.canonicalName.lowercase().contains(q) }
            .sortedBy { it.displayName.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query.value,
                    onValueChange = { query.value = it },
                    label = { Text("Search your pantry") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text("Nothing in the pantry matches.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(shown, key = { it.id }) { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(item) }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(item.displayName, style = MaterialTheme.typography.bodyLarge)
                                if (item.quantity != null) {
                                    Text(
                                        formatQuantityAndUnit(item.quantity, item.quantityUnit),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
