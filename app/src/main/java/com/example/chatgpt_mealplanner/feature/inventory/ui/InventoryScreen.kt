package com.example.chatgpt_mealplanner.feature.inventory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.feature.inventory.InventoryFilter
import com.example.chatgpt_mealplanner.feature.inventory.InventoryUiEvent
import com.example.chatgpt_mealplanner.feature.inventory.InventoryUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    state: InventoryUiState,
    onEvent: (InventoryUiEvent) -> Unit,
    onScanBarcodeClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }
    var correctionItem by remember { mutableStateOf<InventoryItem?>(null) }
    var deleteItemTarget by remember { mutableStateOf<InventoryItem?>(null) }
    var mergeSourceItem by remember { mutableStateOf<InventoryItem?>(null) }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onEvent(InventoryUiEvent.ClearMessage)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = onScanBarcodeClicked,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Barcode")
                }

                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Item")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Search field
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { onEvent(InventoryUiEvent.SearchQueryChanged(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                placeholder = { Text("Search pantry items...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            // Filter chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(InventoryFilter.entries.toTypedArray()) { filter ->
                    FilterChip(
                        selected = state.activeFilter == filter,
                        onClick = { onEvent(InventoryUiEvent.SelectFilter(filter)) },
                        label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No inventory items found.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.filteredItems, key = { it.id }) { item ->
                        InventoryItemCard(
                            item = item,
                            onItemClick = { onEvent(InventoryUiEvent.SelectItemForEdit(item)) },
                            onToggleStaple = { onEvent(InventoryUiEvent.ToggleStaple(item.id)) },
                            onMarkAsOut = { onEvent(InventoryUiEvent.MarkAsOut(item.id)) },
                            onCorrection = { correctionItem = item },
                            onMergeRequested = { mergeSourceItem = item },
                            onDeleteRequested = { deleteItemTarget = item }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddEditItemDialog(
            item = null,
            onDismiss = { showAddDialog = false },
            onSave = { newItem ->
                onEvent(InventoryUiEvent.AddItemRequested(newItem))
                showAddDialog = false
            }
        )
    }

    state.selectedItemForEdit?.let { itemToEdit ->
        AddEditItemDialog(
            item = itemToEdit,
            onDismiss = { onEvent(InventoryUiEvent.SelectItemForEdit(null)) },
            onSave = { updatedItem ->
                onEvent(InventoryUiEvent.UpdateItemRequested(updatedItem))
            }
        )
    }

    deleteItemTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItemTarget = null },
            title = { Text("Delete \"${item.displayName}\"?") },
            text = { Text("This will remove \"${item.displayName}\" from your active inventory while preserving transaction history.") },
            confirmButton = {
                Button(
                    onClick = {
                        onEvent(InventoryUiEvent.DiscardItem(item.id, "User requested deletion"))
                        deleteItemTarget = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteItemTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    mergeSourceItem?.let { source ->
        MergeItemsDialog(
            sourceItem = source,
            availableItems = state.rawItems.filter { it.id != source.id && it.status != ItemStatus.DISCARDED },
            onDismiss = { mergeSourceItem = null },
            onConfirmMerge = { survivorId, mergedId, preferredName ->
                onEvent(InventoryUiEvent.MergeRequested(survivorId, mergedId, preferredName))
                mergeSourceItem = null
            }
        )
    }

    correctionItem?.let { item ->
        CorrectionDialog(
            item = item,
            onDismiss = { correctionItem = null },
            onConfirm = { newQty, newUnit, confidence, note ->
                onEvent(InventoryUiEvent.RecordCorrection(item.id, newQty, newUnit, confidence, note = note))
                correctionItem = null
            }
        )
    }
}

@Composable
fun StatusChip(status: ItemStatus) {
    val (color, text) = when (status) {
        ItemStatus.AVAILABLE -> Color(0xFF2E7D32) to "Available"
        ItemStatus.LOW -> Color(0xFFE65100) to "Low Stock"
        ItemStatus.USE_SOON -> Color(0xFFC62828) to "Use Soon"
        ItemStatus.OUT -> Color(0xFF757575) to "Out of Stock"
        ItemStatus.DISCARDED -> Color(0xFF9E9E9E) to "Discarded"
        ItemStatus.NEEDS_CONFIRMATION -> Color(0xFF1976D2) to "Needs Review"
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun InventoryItemCard(
    item: InventoryItem,
    onItemClick: () -> Unit,
    onToggleStaple: () -> Unit,
    onMarkAsOut: () -> Unit,
    onCorrection: () -> Unit,
    onMergeRequested: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleStaple) {
                        Icon(
                            imageVector = if (item.staple) Icons.Default.Star else Icons.Default.StarOutline,
                            contentDescription = if (item.staple) "Remove from Staples" else "Mark as Staple",
                            tint = if (item.staple) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusChip(status = item.status)

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Item Options")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit Item") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onItemClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Merge with another item") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onMergeRequested()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Item") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onDeleteRequested()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val qtyText = when (item.quantityConfidence) {
                    QuantityConfidence.EXACT -> "${item.quantity ?: 0.0} ${item.quantityUnit?.name ?: ""}"
                    QuantityConfidence.APPROXIMATE -> "Approx. ${item.quantity ?: 0.0} ${item.quantityUnit?.name ?: ""}"
                    QuantityConfidence.UNKNOWN -> "Unknown quantity"
                }

                Text(
                    text = qtyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCorrection) {
                        Text("Correct Qty")
                    }
                    if (item.status != ItemStatus.OUT) {
                        OutlinedButton(onClick = onMarkAsOut) {
                            Text("Mark Out")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddEditItemDialog(
    item: InventoryItem?,
    onDismiss: () -> Unit,
    onSave: (InventoryItem) -> Unit
) {
    var name by remember { mutableStateOf(item?.displayName ?: "") }
    var quantityText by remember { mutableStateOf(item?.quantity?.toString() ?: "1.0") }
    var selectedUnit by remember { mutableStateOf(item?.quantityUnit ?: QuantityUnit.UNIT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Add Inventory Item" else "Edit Inventory Item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Unit", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuantityUnit.entries.forEach { unit ->
                        FilterChip(
                            selected = selectedUnit == unit,
                            onClick = { selectedUnit = unit },
                            label = { Text(unit.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    val qty = quantityText.toDoubleOrNull() ?: 1.0
                    val updated = item?.copy(
                        displayName = name,
                        quantity = qty,
                        quantityUnit = selectedUnit
                    ) ?: InventoryItem(
                        canonicalName = name,
                        displayName = name,
                        category = FoodCategory.CUPBOARD,
                        storageLocation = StorageLocation.CUPBOARD,
                        quantity = qty,
                        quantityUnit = selectedUnit,
                        quantityConfidence = QuantityConfidence.EXACT
                    )
                    onSave(updated)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CorrectionDialog(
    item: InventoryItem,
    onDismiss: () -> Unit,
    onConfirm: (Double, QuantityUnit, QuantityConfidence, String?) -> Unit
) {
    var qtyText by remember { mutableStateOf(item.quantity?.toString() ?: "1.0") }
    var selectedUnit by remember { mutableStateOf(item.quantityUnit ?: QuantityUnit.UNIT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct Quantity & Unit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Item: ${item.displayName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text("New Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Unit", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuantityUnit.entries.forEach { unit ->
                        FilterChip(
                            selected = selectedUnit == unit,
                            onClick = { selectedUnit = unit },
                            label = { Text(unit.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = qtyText.toDoubleOrNull() ?: (item.quantity ?: 1.0)
                    onConfirm(qty, selectedUnit, QuantityConfidence.EXACT, "User correction")
                }
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun MergeItemsDialog(
    sourceItem: InventoryItem,
    availableItems: List<InventoryItem>,
    onDismiss: () -> Unit,
    onConfirmMerge: (survivorId: String, mergedId: String, preferredName: String) -> Unit
) {
    var selectedTargetItem by remember { mutableStateOf<InventoryItem?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    var preferredNameOption by remember { mutableStateOf(sourceItem.displayName) }

    val filteredTargets = availableItems.filter {
        searchQuery.isBlank() || it.displayName.contains(searchQuery, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge Inventory Items") },
        text = {
            Column {
                if (selectedTargetItem == null) {
                    Text(
                        text = "Select an item to merge with \"${sourceItem.displayName}\":",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search items to merge...") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(filteredTargets, key = { it.id }) { target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedTargetItem = target
                                        preferredNameOption = sourceItem.displayName
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = false,
                                    onClick = {
                                        selectedTargetItem = target
                                        preferredNameOption = sourceItem.displayName
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(target.displayName, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${target.quantity ?: 0.0} ${target.quantityUnit?.name ?: ""}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val target = selectedTargetItem!!
                    Text(
                        text = "Merge Preview:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Resulting Display Name:", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = preferredNameOption == sourceItem.displayName,
                            onClick = { preferredNameOption = sourceItem.displayName }
                        )
                        Text(sourceItem.displayName)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = preferredNameOption == target.displayName,
                            onClick = { preferredNameOption = target.displayName }
                        )
                        Text(target.displayName)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    val combinedQty = (sourceItem.quantity ?: 0.0) + (target.quantity ?: 0.0)
                    Text("Resulting Combined Quantity: $combinedQty ${sourceItem.quantityUnit?.name ?: target.quantityUnit?.name ?: ""}")
                    Text("Resulting Staple State: ${if (sourceItem.staple || target.staple) "Staple" else "Not Staple"}")
                }
            }
        },
        confirmButton = {
            if (selectedTargetItem != null) {
                Button(
                    onClick = {
                        val survivorId = if (preferredNameOption == sourceItem.displayName) sourceItem.id else selectedTargetItem!!.id
                        val mergedId = if (survivorId == sourceItem.id) selectedTargetItem!!.id else sourceItem.id
                        onConfirmMerge(survivorId, mergedId, preferredNameOption)
                    }
                ) {
                    Text("Confirm Merge")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
