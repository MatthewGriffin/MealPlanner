package com.example.chatgpt_mealplanner.feature.inventory.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
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
    onSearchProducts: (suspend (String) -> List<com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult>)? = null,
    modifier: Modifier = Modifier,
    onAddLowStockToShopping: ((InventoryItem) -> Unit)? = null,
    onOpenAssistant: (kitName: String) -> Unit = {}
) {
    var kitPromptName by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }
    var correctionItem by remember { mutableStateOf<InventoryItem?>(null) }
    var deleteItemTarget by remember { mutableStateOf<InventoryItem?>(null) }
    var mergeSourceItem by remember { mutableStateOf<InventoryItem?>(null) }
    var showFilterMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onEvent(InventoryUiEvent.ClearMessage)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
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

            // Keep the filter and quick actions above the list so they never cover item controls.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { showFilterMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(state.activeFilter.displayName, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        InventoryFilter.entries.forEach { filter ->
                            DropdownMenuItem(
                                text = { Text(filter.displayName) },
                                onClick = {
                                    showFilterMenu = false
                                    onEvent(InventoryUiEvent.SelectFilter(filter))
                                }
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onScanBarcodeClicked,
                    modifier = Modifier.width(48.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan barcode")
                }
                IconButton(
                    onClick = { onEvent(InventoryUiEvent.OpenBulkAdd) },
                    modifier = Modifier.width(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add many items from a list")
                }
                IconButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.width(48.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add inventory item")
                }
            }

            if (state.isFollowerPaired && state.followerSyncStatus != null) {
                FollowerSyncStatusCard(
                    status = state.followerSyncStatus,
                    onSyncNowClicked = { onEvent(InventoryUiEvent.TriggerManualSync) }
                )
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
                            needsInstructions = item.id in state.kitsWithoutInstructions,
                            onScanPacket = { onOpenAssistant(item.displayName) },
                            onItemClick = { onEvent(InventoryUiEvent.SelectItemForEdit(item)) },
                            onToggleStaple = { onEvent(InventoryUiEvent.ToggleStaple(item.id)) },
                            onMarkAsOut = { onEvent(InventoryUiEvent.MarkAsOut(item.id)) },
                            onCorrection = { correctionItem = item },
                            onMergeRequested = { mergeSourceItem = item },
                            onDeleteRequested = { deleteItemTarget = item },
                            onAddToShoppingList = if (item.status == ItemStatus.LOW && onAddLowStockToShopping != null) {
                                { onAddLowStockToShopping(item) }
                            } else null
                        )
                    }
                }
            }
        }
    }

    state.bulkAdd?.let { BulkAddDialog(state = it, onEvent = onEvent) }

    if (showAddDialog) {
        AddEditItemDialog(
            item = null,
            onDismiss = { showAddDialog = false },
            onSave = { newItem ->
                onEvent(InventoryUiEvent.AddItemRequested(newItem))
                if (newItem.category == FoodCategory.KITS_MIXES) kitPromptName = newItem.displayName
                showAddDialog = false
            },
            onSearchProducts = onSearchProducts
        )
    }

    state.selectedItemForEdit?.let { itemToEdit ->
        AddEditItemDialog(
            item = itemToEdit,
            onDismiss = { onEvent(InventoryUiEvent.SelectItemForEdit(null)) },
            onSave = { updatedItem ->
                onEvent(InventoryUiEvent.UpdateItemRequested(updatedItem))
                if (updatedItem.category == FoodCategory.KITS_MIXES && itemToEdit.category != FoodCategory.KITS_MIXES) {
                    kitPromptName = updatedItem.displayName
                }
            },
            onSearchProducts = onSearchProducts
        )
    }

    kitPromptName?.let { kit ->
        AlertDialog(
            onDismissRequest = { kitPromptName = null },
            title = { Text("Scan the packet now?") },
            text = { Text("\"$kit\" is a kit. Claude needs its packet instructions to cook with it. If you haven't saved them yet, scan it now.") },
            confirmButton = {
                TextButton(onClick = { kitPromptName = null; onOpenAssistant(kit) }, modifier = Modifier.testTag("kit-scan-now")) { Text("Scan packet") }
            },
            dismissButton = { TextButton(onClick = { kitPromptName = null }) { Text("Later") } }
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
    needsInstructions: Boolean = false,
    onScanPacket: () -> Unit = {},
    onItemClick: () -> Unit,
    onToggleStaple: () -> Unit,
    onMarkAsOut: () -> Unit,
    onCorrection: () -> Unit,
    onMergeRequested: () -> Unit,
    onDeleteRequested: () -> Unit,
    onAddToShoppingList: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (needsInstructions) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "No packet instructions yet",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).testTag("kit-no-instructions")
                    )
                    TextButton(onClick = onScanPacket, modifier = Modifier.testTag("kit-scan-packet")) { Text("Scan packet") }
                }
            }
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
            if (onAddToShoppingList != null) {
                Spacer(modifier = Modifier.height(4.dp))
                FilledTonalButton(onClick = onAddToShoppingList, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to shopping list")
                }
            }
        }
    }
}

@Composable
fun AddEditItemDialog(
    item: InventoryItem?,
    onDismiss: () -> Unit,
    onSave: (InventoryItem) -> Unit,
    onSearchProducts: (suspend (String) -> List<com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult>)? = null
) {
    var formState by remember { mutableStateOf(AddEditItemFormState.fromInventoryItem(item)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Add Inventory Item" else "Edit Inventory Item") },
        text = {
            AddEditItemForm(
                formState = formState,
                onFormStateChange = { formState = it },
                onSearchProducts = onSearchProducts
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (formState.displayName.isBlank()) return@Button
                    val updated = formState.toInventoryItem()
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
                EnumDropdownSelector(
                    label = "Unit",
                    options = QuantityUnit.entries.toTypedArray(),
                    selectedOption = selectedUnit,
                    onOptionSelected = { selectedUnit = it },
                    modifier = Modifier.fillMaxWidth()
                )
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
                EnumDropdownSelector(
                    label = "Merge with",
                    options = filteredTargets.toTypedArray<InventoryItem?>(),
                    selectedOption = selectedTargetItem,
                    onOptionSelected = {
                        selectedTargetItem = it
                        preferredNameOption = sourceItem.displayName
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionLabel = { item ->
                        item?.let { "${it.displayName} (${it.quantity ?: 0.0} ${it.quantityUnit?.name ?: ""})".replace(" )", ")") }
                            ?: "Choose an item"
                    }
                )

                selectedTargetItem?.let { target ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Merge Preview:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    EnumDropdownSelector(
                        label = "Resulting Display Name",
                        options = arrayOf(sourceItem.displayName, target.displayName),
                        selectedOption = preferredNameOption,
                        onOptionSelected = { preferredNameOption = it },
                        modifier = Modifier.fillMaxWidth()
                    )

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

@Composable
fun FollowerSyncStatusCard(
    status: com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncStatusModel,
    onSyncNowClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (status.state) {
                com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.ACCESS_REVOKED -> MaterialTheme.colorScheme.errorContainer
                com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.SYNC_ERROR -> MaterialTheme.colorScheme.errorContainer
                com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.PENDING_CHANGES -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title = when (status.state) {
                    com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.UNPAIRED -> "Pairing required"
                    com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.BOOTSTRAP_REQUIRED -> "Initial snapshot required"
                    com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.ACCESS_REVOKED -> "Access revoked by Main"
                    com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.AUTHENTICATION_REQUIRED -> "Authentication required"
                    com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.SYNC_ERROR -> "Sync couldn't connect to Main"
                    com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.SYNCING -> "Syncing with Main..."
                    com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.PENDING_CHANGES -> "${status.pendingUploadCount} change(s) waiting to upload"
                    com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.NO_PENDING_UPLOADS -> "No changes waiting to upload"
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status.state in listOf(
                            com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.PENDING_CHANGES,
                            com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.SYNC_ERROR,
                            com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.AUTHENTICATION_REQUIRED,
                            com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.BOOTSTRAP_REQUIRED
                        )
                    ) {
                        TextButton(onClick = onSyncNowClicked) {
                            Text(if (status.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.BOOTSTRAP_REQUIRED) "Retry Bootstrap" else "Sync Now")
                        }
                    }

                    TextButton(onClick = { isExpanded = !isExpanded }) {
                        Text(if (isExpanded) "Hide Details" else "Details")
                    }
                }
            }

            if (status.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.ACCESS_REVOKED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Access was revoked by the Main device. Your local inventory remains on this device, but this device can no longer sync with the household.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))

                if (status.lastSyncTime != null) {
                    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.US)
                    Text(
                        text = "Last successful refresh from Main: ${sdf.format(Date(status.lastSyncTime))}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "Last successful refresh from Main: None yet",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("Changes not yet confirmed by Main: ${status.pendingUploadCount}", style = MaterialTheme.typography.bodySmall)
                if (status.conflictCount > 0) {
                    Text("Conflicts needing review on Main: ${status.conflictCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (status.pendingApprovalCount > 0) {
                    Text("Approvals waiting for Main: ${status.pendingApprovalCount}", style = MaterialTheme.typography.bodySmall)
                }
                if (status.rejectedCount > 0) {
                    Text("Rejected operations: ${status.rejectedCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (status.canonicalCursor != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Diagnostics: Reconciled Canonical Sequence ${status.canonicalCursor}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
