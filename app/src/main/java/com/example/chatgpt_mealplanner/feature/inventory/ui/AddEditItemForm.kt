package com.example.chatgpt_mealplanner.feature.inventory.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.barcode.BarcodeLookupResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.launch

data class AddEditItemFormState(
    val id: String? = null,
    val displayName: String = "",
    val brand: String = "",
    val barcode: String = "",
    val quantityText: String = "1.0",
    val selectedUnit: QuantityUnit = QuantityUnit.UNIT,
    val category: FoodCategory = FoodCategory.DRY_GOODS,
    val storageLocation: StorageLocation = StorageLocation.CUPBOARD,
    val packCountText: String = "1",
    val packSizeText: String = "",
    val packSizeUnit: QuantityUnit = QuantityUnit.UNIT,
    val purchaseDate: String = "",
    val openedDate: String = "",
    val useByDate: String = "",
    val bestBeforeDate: String = "",
    val opened: Boolean = false,
    val frozen: Boolean = false,
    val staple: Boolean = false,
    val notes: String = "",
    val isPackagingExpanded: Boolean = false,
    val isDatesAndStorageExpanded: Boolean = false,
    val isNotesExpanded: Boolean = false,
    val originalStatus: ItemStatus = ItemStatus.AVAILABLE,
    val originalItem: InventoryItem? = null
) {
    fun toInventoryItem(): InventoryItem {
        val enteredQuantity = quantityText.toDoubleOrNull()
        val qty = enteredQuantity ?: 1.0
        val quantityUnchanged = originalItem != null &&
            quantityText == (originalItem.quantity?.toString() ?: "") &&
            selectedUnit == (originalItem.quantityUnit ?: QuantityUnit.UNIT)
        val pCount = packCountText.toIntOrNull()
        val pSize = packSizeText.toDoubleOrNull()

        return InventoryItem(
            id = id ?: java.util.UUID.randomUUID().toString(),
            canonicalIngredientId = if (originalItem != null) originalItem.canonicalIngredientId else displayName.trim(),
            canonicalName = originalItem?.canonicalName ?: displayName.trim(),
            displayName = displayName.trim(),
            brand = brand.ifBlank { null },
            barcode = barcode.ifBlank { null },
            category = category,
            storageLocation = storageLocation,
            quantity = if (quantityUnchanged) originalItem?.quantity else qty,
            quantityUnit = if (quantityUnchanged) originalItem?.quantityUnit else selectedUnit,
            packCount = pCount,
            packSize = pSize,
            packSizeUnit = if (pSize != null) packSizeUnit else null,
            quantityConfidence = if (quantityUnchanged) originalItem!!.quantityConfidence else QuantityConfidence.EXACT,
            // Editing metadata must not clear stock-state markers. An OUT item
            // becomes available only when a valid positive quantity is entered.
            status = if (originalStatus == ItemStatus.OUT && enteredQuantity != null && enteredQuantity.isFinite() && enteredQuantity > 0.0) {
                ItemStatus.AVAILABLE
            } else {
                originalStatus
            },
            opened = opened,
            frozen = frozen,
            staple = staple,
            purchaseDate = purchaseDate.ifBlank { null },
            openedDate = openedDate.ifBlank { null },
            useByDate = useByDate.ifBlank { null },
            bestBeforeDate = bestBeforeDate.ifBlank { null },
            notes = notes.ifBlank { null },
            createdAt = originalItem?.createdAt ?: System.currentTimeMillis(),
            updatedAt = originalItem?.updatedAt ?: System.currentTimeMillis(),
            revision = originalItem?.revision ?: 1L
        )
    }

    fun applyProductTemplate(
        displayName: String,
        brand: String? = null,
        barcode: String? = null,
        category: FoodCategory = FoodCategory.DRY_GOODS,
        storageLocation: StorageLocation = StorageLocation.CUPBOARD,
        packCount: Int? = null,
        packSize: Double? = null,
        packSizeUnit: QuantityUnit? = null,
        netQuantityUnit: QuantityUnit? = null
    ): AddEditItemFormState {
        return copy(
            displayName = displayName,
            brand = brand ?: this.brand,
            barcode = barcode ?: this.barcode,
            category = category,
            storageLocation = storageLocation,
            packCountText = packCount?.toString() ?: this.packCountText,
            packSizeText = packSize?.toString() ?: this.packSizeText,
            packSizeUnit = packSizeUnit ?: this.packSizeUnit,
            selectedUnit = netQuantityUnit ?: this.selectedUnit,
            isPackagingExpanded = brand != null || barcode != null || packCount != null || packSize != null
        )
    }

    companion object {
        fun fromInventoryItem(item: InventoryItem?): AddEditItemFormState {
            if (item == null) return AddEditItemFormState()
            return AddEditItemFormState(
                id = item.id,
                displayName = item.displayName,
                brand = item.brand ?: "",
                barcode = item.barcode ?: "",
                quantityText = item.quantity?.toString() ?: "",
                selectedUnit = item.quantityUnit ?: QuantityUnit.UNIT,
                category = item.category,
                storageLocation = item.storageLocation,
                packCountText = item.packCount?.toString() ?: "1",
                packSizeText = item.packSize?.toString() ?: "",
                packSizeUnit = item.packSizeUnit ?: QuantityUnit.UNIT,
                purchaseDate = item.purchaseDate ?: "",
                openedDate = item.openedDate ?: "",
                useByDate = item.useByDate ?: "",
                bestBeforeDate = item.bestBeforeDate ?: "",
                opened = item.opened ?: false,
                frozen = item.frozen,
                staple = item.staple,
                notes = item.notes ?: "",
                isPackagingExpanded = item.brand != null || item.barcode != null || item.packCount != null || item.packSize != null,
                isDatesAndStorageExpanded = item.useByDate != null || item.bestBeforeDate != null || item.purchaseDate != null || item.openedDate != null,
                isNotesExpanded = item.notes != null,
                originalStatus = item.status,
                originalItem = item
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> EnumDropdownSelector(
    label: String,
    options: Array<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() }
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = optionLabel(selectedOption),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SearchProductDialog(
    onDismiss: () -> Unit,
    onSearchQuery: suspend (String) -> List<BarcodeLookupResult>,
    onSelectProduct: (BarcodeLookupResult) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<BarcodeLookupResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search Food Products", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Search saved local products and online Open Food Facts database:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { query ->
                        searchQuery = query
                        searchJob?.cancel()
                        if (query.trim().length >= 2) {
                            isSearching = true
                            searchJob = coroutineScope.launch {
                                val results = onSearchQuery(query.trim())
                                if (searchQuery.trim() == query.trim()) {
                                    searchResults = results
                                    isSearching = false
                                }
                            }
                        } else {
                            searchResults = emptyList()
                            isSearching = false
                        }
                    },
                    label = { Text("Search Product Name or Brand") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (searchQuery.trim().length >= 2 && searchResults.isEmpty()) {
                    Text(
                        "No matching products found. You can enter details manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else if (searchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(searchResults) { result ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectProduct(result)
                                        onDismiss()
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = result.displayName,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )

                                        val (label, containerColor) = when (result.sourceName) {
                                            "USER_CONFIRMED_MAPPING", "SAVED" -> "Saved Product" to MaterialTheme.colorScheme.primaryContainer
                                            "OPEN_FOOD_FACTS" -> "Open Food Facts" to MaterialTheme.colorScheme.secondaryContainer
                                            else -> "External Match" to MaterialTheme.colorScheme.tertiaryContainer
                                        }

                                        Surface(
                                            color = containerColor,
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    val subText = listOfNotNull(
                                        result.brand,
                                        result.category.name.lowercase().replaceFirstChar { it.uppercase() },
                                        result.barcode.takeIf { it.isNotBlank() }
                                    ).joinToString(" • ")

                                    if (subText.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(subText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun AddEditItemForm(
    formState: AddEditItemFormState,
    onFormStateChange: (AddEditItemFormState) -> Unit,
    modifier: Modifier = Modifier,
    onSearchProducts: (suspend (String) -> List<BarcodeLookupResult>)? = null
) {
    var showSearchDialog by remember { mutableStateOf(false) }

    if (showSearchDialog && onSearchProducts != null) {
        SearchProductDialog(
            onDismiss = { showSearchDialog = false },
            onSearchQuery = onSearchProducts,
            onSelectProduct = { result ->
                val updated = formState.applyProductTemplate(
                    displayName = result.displayName,
                    brand = result.brand,
                    barcode = result.barcode,
                    category = result.category,
                    storageLocation = result.storageLocation,
                    packCount = result.packCount,
                    packSize = result.packSize,
                    packSizeUnit = result.packSizeUnit,
                    netQuantityUnit = result.netQuantityUnit
                )
                onFormStateChange(updated)
            }
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Essential Primary Fields
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = formState.displayName,
                onValueChange = { onFormStateChange(formState.copy(displayName = it)) },
                label = { Text("Item Name *") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            if (onSearchProducts != null) {
                TextButton(onClick = { showSearchDialog = true }) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Search")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = formState.quantityText,
                onValueChange = { onFormStateChange(formState.copy(quantityText = it)) },
                label = { Text("Quantity") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            EnumDropdownSelector(
                label = "Unit",
                options = QuantityUnit.entries.toTypedArray(),
                selectedOption = formState.selectedUnit,
                onOptionSelected = { onFormStateChange(formState.copy(selectedUnit = it)) },
                modifier = Modifier.weight(1f),
                optionLabel = { it.name }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EnumDropdownSelector(
                label = "Category",
                options = FoodCategory.entries.toTypedArray(),
                selectedOption = formState.category,
                onOptionSelected = { onFormStateChange(formState.copy(category = it)) },
                modifier = Modifier.weight(1f),
                optionLabel = { it.displayName }
            )

            EnumDropdownSelector(
                label = "Storage Location",
                options = StorageLocation.entries.toTypedArray(),
                selectedOption = formState.storageLocation,
                onOptionSelected = { onFormStateChange(formState.copy(storageLocation = it)) },
                modifier = Modifier.weight(1f),
                optionLabel = { it.displayName }
            )
        }

        // Section 1: Progressive Disclosure - Packaging & Brand
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFormStateChange(formState.copy(isPackagingExpanded = !formState.isPackagingExpanded)) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Packaging & Brand Details", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Icon(
                        imageVector = if (formState.isPackagingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = formState.isPackagingExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = formState.brand,
                            onValueChange = { onFormStateChange(formState.copy(brand = it)) },
                            label = { Text("Brand") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = formState.barcode,
                            onValueChange = { onFormStateChange(formState.copy(barcode = it)) },
                            label = { Text("Barcode (UPC / EAN)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = formState.packCountText,
                                onValueChange = { onFormStateChange(formState.copy(packCountText = it)) },
                                label = { Text("Pack Count") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )

                            OutlinedTextField(
                                value = formState.packSizeText,
                                onValueChange = { onFormStateChange(formState.copy(packSizeText = it)) },
                                label = { Text("Pack Size") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        EnumDropdownSelector(
                            label = "Pack Size Unit",
                            options = QuantityUnit.entries.toTypedArray(),
                            selectedOption = formState.packSizeUnit,
                            onOptionSelected = { onFormStateChange(formState.copy(packSizeUnit = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            optionLabel = { it.name }
                        )
                    }
                }
            }
        }

        // Section 2: Progressive Disclosure - Dates & Toggles
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFormStateChange(formState.copy(isDatesAndStorageExpanded = !formState.isDatesAndStorageExpanded)) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dates & Attributes", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Icon(
                        imageVector = if (formState.isDatesAndStorageExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = formState.isDatesAndStorageExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = formState.useByDate,
                            onValueChange = { onFormStateChange(formState.copy(useByDate = it)) },
                            label = { Text("Use By Date (YYYY-MM-DD)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = formState.bestBeforeDate,
                            onValueChange = { onFormStateChange(formState.copy(bestBeforeDate = it)) },
                            label = { Text("Best Before Date (YYYY-MM-DD)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = formState.staple,
                                    onCheckedChange = { onFormStateChange(formState.copy(staple = it)) }
                                )
                                Text("Mark as Staple")
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = formState.frozen,
                                    onCheckedChange = { onFormStateChange(formState.copy(frozen = it)) }
                                )
                                Text("Frozen")
                            }
                        }
                    }
                }
            }
        }

        // Section 3: Progressive Disclosure - Notes
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFormStateChange(formState.copy(isNotesExpanded = !formState.isNotesExpanded)) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Additional Notes", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Icon(
                        imageVector = if (formState.isNotesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = formState.isNotesExpanded) {
                    OutlinedTextField(
                        value = formState.notes,
                        onValueChange = { onFormStateChange(formState.copy(notes = it)) },
                        label = { Text("Notes") },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}
