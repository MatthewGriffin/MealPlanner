package com.example.chatgpt_mealplanner.feature.mealplanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

// Immutable so that replacing a row in the state list is what redraws it.
data class EditableIngredient(
    val source: MealIngredient? = null,
    val id: String = source?.id ?: UUID.randomUUID().toString(),
    val displayName: String = source?.displayName ?: "",
    val plannedQuantity: String = source?.plannedQuantity?.toString() ?: "100.0",
    val optional: Boolean = source?.optional ?: false
) {
    /** Returns null for a row with no name. Fields this dialog doesn't show keep their saved values. */
    fun toMealIngredient(mealId: String): MealIngredient? {
        if (displayName.isBlank()) return null
        // A renamed ingredient is a different food, so it must not match the old one's stock.
        val canonicalId = source?.canonicalIngredientId
            ?.takeIf { it.isNotBlank() && displayName == source.displayName }
            ?: displayName.uppercase().replace(" ", "_")
        val base = source ?: MealIngredient(
            id = id,
            mealId = mealId,
            displayName = displayName,
            plannedQuantity = 0.0,
            plannedUnit = QuantityUnit.GRAM
        )
        return base.copy(
            mealId = mealId,
            canonicalIngredientId = canonicalId,
            displayName = displayName,
            plannedQuantity = plannedQuantity.toDoubleOrNull() ?: 100.0,
            optional = optional
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditMealDialog(
    mealWithIngredients: MealWithIngredients?,
    selectedDate: String,
    onDismiss: () -> Unit,
    onSaveMeal: (meal: Meal, ingredients: List<MealIngredient>) -> Unit
) {
    val initialMeal = mealWithIngredients?.meal
    val initialIngredients = mealWithIngredients?.ingredients

    var title by remember { mutableStateOf(initialMeal?.title ?: "") }
    var date by remember { mutableStateOf(initialMeal?.date ?: selectedDate.ifEmpty { LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) }) }
    var mealType by remember { mutableStateOf(initialMeal?.mealType ?: MealType.DINNER) }
    var servings by remember { mutableStateOf(initialMeal?.servings?.toString() ?: "2") }
    var immediatePortions by remember { mutableStateOf(initialMeal?.immediatePortions?.toString() ?: "2") }
    var leftoverPortions by remember { mutableStateOf(initialMeal?.leftoverPortions?.toString() ?: "0") }
    var mealTypeExpanded by remember { mutableStateOf(false) }

    val ingredientsList = remember {
        mutableStateListOf<EditableIngredient>().apply {
            if (initialIngredients.isNullOrEmpty()) {
                add(EditableIngredient())
            } else {
                addAll(initialIngredients.map { EditableIngredient(source = it) })
            }
        }
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialMeal == null) "Add Planned Meal" else "Edit Planned Meal")
        },
        text = {
            Column {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Meal Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Date (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    ExposedDropdownMenuBox(
                        expanded = mealTypeExpanded,
                        onExpandedChange = { mealTypeExpanded = !mealTypeExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = mealType.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mealTypeExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = mealTypeExpanded,
                            onDismissRequest = { mealTypeExpanded = false }
                        ) {
                            MealType.values().forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.name) },
                                    onClick = {
                                        mealType = type
                                        mealTypeExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = servings,
                        onValueChange = { servings = it },
                        label = { Text("Servings") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = immediatePortions,
                        onValueChange = { immediatePortions = it },
                        label = { Text("Immediate") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = leftoverPortions,
                        onValueChange = { leftoverPortions = it },
                        label = { Text("Leftover") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ingredients",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { ingredientsList.add(EditableIngredient()) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Ingredient")
                    }
                }

                LazyColumn(modifier = Modifier.height(200.dp)) {
                    itemsIndexed(ingredientsList) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = item.displayName,
                                onValueChange = { ingredientsList[index] = item.copy(displayName = it) },
                                label = { Text("Name") },
                                singleLine = true,
                                modifier = Modifier.weight(1.2f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            OutlinedTextField(
                                value = item.plannedQuantity,
                                onValueChange = { ingredientsList[index] = item.copy(plannedQuantity = it) },
                                label = { Text("Qty") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(0.8f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Opt", style = MaterialTheme.typography.bodySmall)
                                Checkbox(
                                    checked = item.optional,
                                    onCheckedChange = { ingredientsList[index] = item.copy(optional = it) }
                                )
                            }
                            IconButton(onClick = { if (ingredientsList.size > 1) ingredientsList.removeAt(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pServings = servings.toIntOrNull() ?: 2
                    val pImm = immediatePortions.toIntOrNull() ?: 2
                    val pLeft = leftoverPortions.toIntOrNull() ?: 0
                    val pBatch = pImm + pLeft

                    if (title.isBlank()) {
                        errorMessage = "Title cannot be blank."
                        return@Button
                    }
                    if (pServings <= 0 || pImm < 0 || pLeft < 0 || pBatch <= 0) {
                        errorMessage = "Portions must be positive."
                        return@Button
                    }

                    val mealId = initialMeal?.id ?: UUID.randomUUID().toString()
                    val finalIngredients = ingredientsList.mapNotNull { it.toMealIngredient(mealId) }

                    if (finalIngredients.isEmpty()) {
                        errorMessage = "At least one valid ingredient is required."
                        return@Button
                    }

                    // Copy an existing meal, so the method, notes and tags survive the edit.
                    val meal = (initialMeal ?: Meal(id = mealId, date = date, title = title)).copy(
                        date = date,
                        mealType = mealType,
                        title = title,
                        servings = pServings,
                        batchPortions = pBatch,
                        immediatePortions = pImm,
                        leftoverPortions = pLeft
                    )

                    onSaveMeal(meal, finalIngredients)
                }
            ) {
                Text("Save Meal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
