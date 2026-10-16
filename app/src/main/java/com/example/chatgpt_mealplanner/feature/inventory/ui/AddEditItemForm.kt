package com.example.chatgpt_mealplanner.feature.inventory.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation

data class AddEditItemFormState(
    val id: String? = null,
    val displayName: String = "",
    val brand: String = "",
    val barcode: String = "",
    val quantityText: String = "1.0",
    val selectedUnit: QuantityUnit = QuantityUnit.UNIT,
    val category: FoodCategory = FoodCategory.CUPBOARD,
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
    val isNotesExpanded: Boolean = false
) {
    fun toInventoryItem(): InventoryItem {
        val qty = quantityText.toDoubleOrNull() ?: 1.0
        val pCount = packCountText.toIntOrNull()
        val pSize = packSizeText.toDoubleOrNull()

        return InventoryItem(
            id = id ?: java.util.UUID.randomUUID().toString(),
            canonicalIngredientId = displayName.trim(),
            canonicalName = displayName.trim(),
            displayName = displayName.trim(),
            brand = brand.ifBlank { null },
            barcode = barcode.ifBlank { null },
            category = category,
            storageLocation = storageLocation,
            quantity = qty,
            quantityUnit = selectedUnit,
            packCount = pCount,
            packSize = pSize,
            packSizeUnit = if (pSize != null) packSizeUnit else null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            opened = opened,
            frozen = frozen,
            staple = staple,
            purchaseDate = purchaseDate.ifBlank { null },
            openedDate = openedDate.ifBlank { null },
            useByDate = useByDate.ifBlank { null },
            bestBeforeDate = bestBeforeDate.ifBlank { null },
            notes = notes.ifBlank { null }
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
                quantityText = (item.quantity ?: 1.0).toString(),
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
                isNotesExpanded = item.notes != null
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
                .menuAnchor()
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
fun AddEditItemForm(
    formState: AddEditItemFormState,
    onFormStateChange: (AddEditItemFormState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Essential Primary Fields
        OutlinedTextField(
            value = formState.displayName,
            onValueChange = { onFormStateChange(formState.copy(displayName = it)) },
            label = { Text("Item Name *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

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
                optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } }
            )

            EnumDropdownSelector(
                label = "Storage Location",
                options = StorageLocation.entries.toTypedArray(),
                selectedOption = formState.storageLocation,
                onOptionSelected = { onFormStateChange(formState.copy(storageLocation = it)) },
                modifier = Modifier.weight(1f),
                optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } }
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
