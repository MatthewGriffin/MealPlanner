package com.example.chatgpt_mealplanner.feature.scanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductConfirmationScreen(
    barcode: String?,
    metadata: ProductMetadata,
    onBack: () -> Unit,
    onConfirm: (ConfirmedProductInput) -> Unit,
    modifier: Modifier = Modifier
) {
    var displayName by remember(metadata.barcode, metadata.canonicalName, metadata.displayName) {
        mutableStateOf(metadata.canonicalName ?: metadata.displayName ?: "")
    }
    var brand by remember(metadata.barcode, metadata.brand) {
        mutableStateOf(metadata.brand ?: "")
    }
    var quantityText by remember(metadata.barcode, metadata.netQuantity) {
        mutableStateOf(metadata.netQuantity?.toString() ?: "")
    }
    var packCountText by remember(metadata.barcode, metadata.packCount) {
        mutableStateOf(metadata.packCount?.toString() ?: "")
    }
    var packSizeText by remember(metadata.barcode, metadata.packSize) {
        mutableStateOf(metadata.packSize?.toString() ?: "")
    }
    var selectedCategory by remember(metadata.barcode, metadata.category) {
        mutableStateOf(metadata.category ?: FoodCategory.CUPBOARD)
    }
    var selectedLocation by remember(metadata.barcode, metadata.suggestedStorageLocation) {
        mutableStateOf(metadata.suggestedStorageLocation ?: StorageLocation.CUPBOARD)
    }
    var selectedUnit by remember(metadata.barcode, metadata.netQuantityUnit) {
        mutableStateOf<QuantityUnit?>(metadata.netQuantityUnit)
    }
    var isOpened by remember { mutableStateOf(false) }
    var useByDateText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    val confirmLog = "[BARCODE_METADATA] stage=CONFIRMATION barcode=$barcode displayName=\"${metadata.displayName ?: ""}\" canonicalName=\"${metadata.canonicalName ?: ""}\" initialProductName=\"$displayName\" netQuantity=${metadata.netQuantity} netQuantityUnit=${metadata.netQuantityUnit}"
    println(confirmLog)
    runCatching { android.util.Log.i("AI_MEALPLANNER", confirmLog) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            if (!barcode.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Barcode: $barcode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            val observationText = listOfNotNull(
                                metadata.brand?.takeIf { it.isNotBlank() },
                                metadata.displayName?.takeIf { it.isNotBlank() }
                            ).joinToString(" • ")
                            if (observationText.isNotBlank()) {
                                Text(
                                    text = "Scanned: $observationText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                )
                            }
                            if (metadata.source != null) {
                                Text(
                                    text = "Source: ${metadata.source.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Product Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = brand,
                onValueChange = { brand = it },
                label = { Text("Brand (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Net Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Quantity Unit *", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (quantityText.toDoubleOrNull() != null && selectedUnit == null) {
                Text("Please select a unit for the quantity", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = packCountText,
                    onValueChange = { packCountText = it },
                    label = { Text("Pack Count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = packSizeText,
                    onValueChange = { packSizeText = it },
                    label = { Text("Size Per Pack") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Storage Location", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StorageLocation.entries.forEach { loc ->
                    FilterChip(
                        selected = selectedLocation == loc,
                        onClick = { selectedLocation = loc },
                        label = { Text(loc.name) }
                    )
                }
            }

            Text("Category", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FoodCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat.name) }
                    )
                }
            }

            OutlinedTextField(
                value = useByDateText,
                onValueChange = { useByDateText = it },
                label = { Text("Use-By Date (YYYY-MM-DD)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = isOpened,
                    onCheckedChange = { isOpened = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Package Opened", style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = notesText,
                onValueChange = { notesText = it },
                label = { Text("Notes (Optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val netQty = quantityText.toDoubleOrNull()
                    if (displayName.isBlank() || (netQty != null && selectedUnit == null)) return@Button

                    val packCount = packCountText.toIntOrNull()
                    val packSize = packSizeText.toDoubleOrNull()

                    val confidence = if (netQty != null || (packCount != null && packSize != null)) {
                        QuantityConfidence.EXACT
                    } else {
                        QuantityConfidence.UNKNOWN
                    }

                    val input = ConfirmedProductInput(
                        barcode = barcode,
                        displayName = displayName,
                        brand = brand.ifBlank { null },
                        canonicalIngredientId = metadata.canonicalIngredientId,
                        canonicalName = metadata.canonicalName ?: displayName,
                        category = selectedCategory,
                        storageLocation = selectedLocation,
                        netQuantity = netQty,
                        netQuantityUnit = selectedUnit,
                        packCount = packCount,
                        packSize = packSize,
                        packSizeUnit = selectedUnit,
                        quantityConfidence = confidence,
                        opened = isOpened,
                        useByDate = useByDateText.ifBlank { null },
                        notes = notesText.ifBlank { null }
                    )
                    onConfirm(input)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add to Inventory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
}
