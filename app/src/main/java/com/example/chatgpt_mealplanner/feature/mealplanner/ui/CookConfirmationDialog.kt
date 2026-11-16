package com.example.chatgpt_mealplanner.feature.mealplanner.ui

import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.ActualIngredientOverride
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookConfirmationDialog(
    mealWithIngredients: MealWithIngredients,
    onDismiss: () -> Unit,
    onConfirmCook: (
        actualIngredientOverrides: List<ActualIngredientOverride>,
        leftoverStorageLocation: StorageLocation
    ) -> Unit
) {
    val meal = mealWithIngredients.meal
    val ingredients = mealWithIngredients.ingredients

    val overrideMap = remember { mutableStateMapOf<String, String>() }
    var leftoverLocation by remember { mutableStateOf(StorageLocation.FREEZER) }
    var locationExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Confirm Cooking: ${meal.title}")
        },
        text = {
            Column {
                Text(
                    text = "Review ingredient quantities to deduct from inventory.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (meal.leftoverPortions > 0) {
                    Text(
                        text = "Batch Meal: ${meal.leftoverPortions} leftover portion(s) will be created.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = locationExpanded,
                        onExpandedChange = { locationExpanded = !locationExpanded }
                    ) {
                        OutlinedTextField(
                            value = leftoverLocation.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Leftover Storage Location") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = locationExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = locationExpanded,
                            onDismissRequest = { locationExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("FRIDGE") },
                                onClick = {
                                    leftoverLocation = StorageLocation.FRIDGE
                                    locationExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("FREEZER") },
                                onClick = {
                                    leftoverLocation = StorageLocation.FREEZER
                                    locationExpanded = false
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = "Ingredients:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(modifier = Modifier.height(240.dp)) {
                    items(ingredients, key = { it.id }) { ing ->
                        val currentText = overrideMap[ing.id] ?: ing.plannedQuantity.toString()

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ing.displayName,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val tagText = if (ing.optional) " (Optional)" else " (Required)"
                                    Text(
                                        text = "Planned: ${ing.plannedQuantity} ${ing.plannedUnit.name}$tagText",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                OutlinedTextField(
                                    value = currentText,
                                    onValueChange = { overrideMap[ing.id] = it },
                                    label = { Text("Actual") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(110.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val overrides = ingredients.mapNotNull { ing ->
                        val text = overrideMap[ing.id] ?: return@mapNotNull null
                        val parsed = text.toDoubleOrNull() ?: return@mapNotNull null
                        if (parsed != ing.plannedQuantity) {
                            ActualIngredientOverride(
                                mealIngredientId = ing.id,
                                actualQuantity = parsed,
                                actualUnit = ing.plannedUnit
                            )
                        } else null
                    }
                    onConfirmCook(overrides, leftoverLocation)
                }
            ) {
                Text("Confirm & Deduct Stock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
