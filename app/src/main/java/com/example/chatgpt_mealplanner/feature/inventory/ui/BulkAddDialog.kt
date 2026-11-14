package com.example.chatgpt_mealplanner.feature.inventory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.feature.inventory.BulkAddRow
import com.example.chatgpt_mealplanner.feature.inventory.BulkAddState
import com.example.chatgpt_mealplanner.feature.inventory.InventoryUiEvent
import com.example.chatgpt_mealplanner.feature.inventory.choiceLabel

/**
 * Add many items at once: type, paste or dictate what you have, let Claude read it into stock
 * lines, then check and save them all.
 */
@Composable
fun BulkAddDialog(
    state: BulkAddState,
    onEvent: (InventoryUiEvent) -> Unit,
    usage: com.example.chatgpt_mealplanner.core.quota.UsageSnapshot = com.example.chatgpt_mealplanner.core.quota.UsageSnapshot(unlimited = true),
    onOpenPlans: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = { if (!state.isSaving) onEvent(InventoryUiEvent.CloseBulkAdd) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.rows.isEmpty()) "Add many items" else "Check ${state.rows.size} items",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onEvent(InventoryUiEvent.CloseBulkAdd) }, enabled = !state.isSaving) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                if (state.rows.isEmpty()) {
                    com.example.chatgpt_mealplanner.feature.subscription.UsageLine(usage, onOpenPlans)
                    BulkAddInput(state, onEvent)
                } else BulkAddReview(state, onEvent)
            }
        }
    }
}

@Composable
private fun ColumnScope.BulkAddInput(state: BulkAddState, onEvent: (InventoryUiEvent) -> Unit) {
    Text(
        "List what you have at home, in any order, with amounts if you know them. " +
            "You can type, paste, or use the keyboard's microphone. For example: " +
            "\"2 kg basmati rice, 3 tins chopped tomatoes, olive oil, ground cumin, a dozen eggs, frozen peas\".",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    OutlinedTextField(
        value = state.text,
        onValueChange = { onEvent(InventoryUiEvent.BulkAddTextChanged(it)) },
        label = { Text("What do you have?") },
        enabled = !state.isReading,
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("bulk-add-text")
    )
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { onEvent(InventoryUiEvent.ReadBulkAddList) },
        enabled = state.text.isNotBlank() && !state.isReading,
        modifier = Modifier.fillMaxWidth().testTag("bulk-add-read")
    ) {
        if (state.isReading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(state.progress ?: "Reading your list…")
        } else {
            Text("Read my list")
        }
    }
}

@Composable
private fun BulkAddHint() {
    Text(
        "Each item was looked up in the food database. Where it wasn't clear which product you mean, choose one (or keep your own wording). Items marked \"guess\" had no amount, so one pack was assumed. Tap an item to change it.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ColumnScope.BulkAddReview(state: BulkAddState, onEvent: (InventoryUiEvent) -> Unit) {
    BulkAddHint()
    state.lookupNote?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
    }
    val failedLookups = state.rows.count { it.lookupFailed }
    if (failedLookups > 0) {
        OutlinedButton(
            onClick = { onEvent(InventoryUiEvent.RetryBulkLookup) },
            enabled = !state.isReading && !state.isSaving,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) { Text(if (state.isReading) (state.progress ?: "Looking up…") else "Look up the $failedLookups that failed again") }
    }
    val undecidedRows = state.rows.count { !it.decided }
    if (undecidedRows > 1) {
        OutlinedButton(
            onClick = { onEvent(InventoryUiEvent.KeepWordingForUndecided) },
            enabled = !state.isReading && !state.isSaving,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) { Text("Keep my wording for the other $undecidedRows") }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        items(state.rows, key = { it.id }) { row ->
            BulkAddRowEditor(row, onChange = { onEvent(InventoryUiEvent.BulkAddRowChanged(it)) }, onRemove = { onEvent(InventoryUiEvent.BulkAddRowRemoved(row.id)) })
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedButton(
            onClick = { onEvent(InventoryUiEvent.EditBulkAddList) },
            enabled = !state.isSaving,
            modifier = Modifier.weight(1f)
        ) { Text("Edit list") }
        val undecided = state.rows.count { !it.decided }
        Button(
            onClick = { onEvent(InventoryUiEvent.SaveBulkAddRows) },
            enabled = !state.isSaving && undecided == 0,
            modifier = Modifier.weight(2f).testTag("bulk-add-save")
        ) {
            if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else if (undecided > 0) Text("Choose $undecided product${if (undecided == 1) "" else "s"} first")
            else Text("Save ${state.rows.size} items")
        }
    }
}

@Composable
private fun BulkAddRowEditor(row: BulkAddRow, onChange: (BulkAddRow) -> Unit, onRemove: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).clickable { editing = true }.padding(vertical = 6.dp)) {
                Text(row.displayName.ifBlank { "(no name)" }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${row.quantityText} ${row.unit.name.lowercase()}${if (row.quantityGuessed) " (guess)" else ""} · " +
                        "${row.storageLocation.displayName} · ${row.category.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                row.matched?.let {
                    Text("Matched: ${it.choiceLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (!row.decided) {
                    Text("Which product do you mean?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = "Edit ${row.displayName}") }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove ${row.displayName}") }
        }
        if (!row.decided) ProductChooser(row, onChange)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (row.candidates.isNotEmpty()) ProductChooser(row, onChange)
        OutlinedTextField(
            value = row.displayName,
            onValueChange = { onChange(row.copy(displayName = it)) },
            label = { Text("Item") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = row.quantityText,
                onValueChange = { onChange(row.copy(quantityText = it, quantityGuessed = false)) },
                label = { Text(if (row.quantityGuessed) "Amount (guess)" else "Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            EnumDropdownSelector(
                label = "Unit",
                options = QuantityUnit.entries.toTypedArray(),
                selectedOption = row.unit,
                onOptionSelected = { onChange(row.copy(unit = it)) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            EnumDropdownSelector(
                label = "Kept in",
                options = StorageLocation.entries.toTypedArray(),
                selectedOption = row.storageLocation,
                onOptionSelected = { onChange(row.copy(storageLocation = it)) },
                modifier = Modifier.weight(1f),
                optionLabel = { it.displayName }
            )
            EnumDropdownSelector(
                label = "Category",
                options = FoodCategory.entries.toTypedArray(),
                selectedOption = row.category,
                onOptionSelected = { onChange(row.copy(category = it)) },
                modifier = Modifier.weight(1f),
                optionLabel = { it.displayName }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { editing = false }) { Text("Done") }
            OutlinedButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

/** One option of the product list: a food database product, or the user's own wording. */
private data class ProductOption(val product: com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult?, val label: String)

@Composable
private fun ProductChooser(row: BulkAddRow, onChange: (BulkAddRow) -> Unit) {
    val keepMine = ProductOption(null, "Keep as I wrote it: ${row.genericName}")
    val options = (row.candidates.map { ProductOption(it, it.choiceLabel()) } + keepMine).toTypedArray()
    val selected = when {
        row.matched != null -> options.firstOrNull { it.product == row.matched } ?: keepMine
        row.decided -> keepMine
        else -> ProductOption(null, "Choose a product…")
    }
    EnumDropdownSelector(
        label = "Product",
        options = options,
        selectedOption = selected,
        onOptionSelected = { option ->
            val product = option.product
            onChange(
                if (product == null) {
                    row.copy(matched = null, decided = true)
                } else {
                    // The inventory name stays generic; the product only supplies its barcode, brand and size.
                    row.copy(matched = product, decided = true)
                }
            )
        },
        modifier = Modifier.fillMaxWidth(),
        optionLabel = { it.label }
    )
}
