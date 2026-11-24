package com.example.chatgpt_mealplanner.feature.mealplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.ActualIngredientOverride
import com.example.chatgpt_mealplanner.core.util.formatQuantityAndUnit
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerUiEvent
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerViewModel

@Composable
fun CookNowScreen(
    plannedMealId: String,
    viewModel: MealPlannerViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val allMeals = uiState.mealsForSelectedDate + uiState.recentCookedMeals
    val mealWithIngredients = allMeals.firstOrNull { it.meal.id == plannedMealId }

    val actualQuantities = remember(plannedMealId, mealWithIngredients) {
        mutableStateMapOf<String, String>().apply {
            mealWithIngredients?.ingredients?.forEach { ing ->
                this[ing.id] = ing.plannedQuantity.toString()
            }
        }
    }

    // What the household has of each ingredient, so a short stock is seen before cooking and a scrap can be used up.
    val stock = remember(plannedMealId) { mutableStateMapOf<String, Double>() }
    LaunchedEffect(mealWithIngredients?.meal?.id) {
        stock.clear()
        stock.putAll(viewModel.stockFor(mealWithIngredients?.ingredients.orEmpty()))
    }

    // Ingredients whose name matched nothing in the pantry can be pointed at the pantry item that is the same thing.
    val picked = remember(plannedMealId) { mutableStateMapOf<String, com.example.chatgpt_mealplanner.core.model.InventoryItem>() }
    var pantry by remember { mutableStateOf<List<com.example.chatgpt_mealplanner.core.model.InventoryItem>>(emptyList()) }
    var pickFor by remember { mutableStateOf<com.example.chatgpt_mealplanner.core.model.MealIngredient?>(null) }
    LaunchedEffect(plannedMealId) { pantry = viewModel.pantryItems() }
    pickFor?.let { target ->
        com.example.chatgpt_mealplanner.feature.common.PantryItemPickerDialog(
            title = "Which pantry item is \"${target.displayName}\"?",
            items = pantry,
            onPick = { picked[target.id] = it; pickFor = null },
            onDismiss = { pickFor = null }
        )
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Text(
                text = "Confirm Cooking",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (mealWithIngredients == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Meal not found",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onNavigateBack) {
                    Text("Go Back")
                }
            }
        } else {
            val meal = mealWithIngredients.meal
            val ingredients = mealWithIngredients.ingredients

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = meal.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Actual quantities can be adjusted before stock is deducted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(ingredients, key = { it.id }) { ing ->
                    val currentActual = actualQuantities[ing.id] ?: ing.plannedQuantity.toString()
                    val plannedFormatted = formatQuantityAndUnit(ing.plannedQuantity, ing.plannedUnit)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = ing.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Planned: $plannedFormatted",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val pickedItem = picked[ing.id]
                            val inStock = stock[ing.id] ?: pickedItem?.takeIf { it.quantityUnit == ing.plannedUnit }?.quantity
                            val entered = currentActual.toDoubleOrNull() ?: ing.plannedQuantity
                            if (inStock != null) {
                                Text(
                                    text = "In stock: ${formatQuantityAndUnit(inStock, ing.plannedUnit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entered > inStock + 1e-9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (entered > inStock + 1e-9) {
                                    Text(
                                        text = "That is more than you have: ${formatQuantityAndUnit(entered - inStock, ing.plannedUnit)} short.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else if (com.example.chatgpt_mealplanner.core.util.StockRemainder.isSmallRemainder(inStock, entered, ing.plannedUnit)) {
                                    Text(
                                        text = "Only ${formatQuantityAndUnit(inStock - entered, ing.plannedUnit)} would be left, so it will be used up.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (pickedItem == null) {
                                Text(
                                    text = "Not found in your pantry under this name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (pickedItem != null) {
                                Text(
                                    text = "Using ${pickedItem.displayName} from your pantry",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (stock[ing.id] == null) {
                                androidx.compose.material3.TextButton(onClick = { pickFor = ing }) {
                                    Text(if (pickedItem == null) "Pick from pantry" else "Change pantry item")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = currentActual,
                                    onValueChange = { actualQuantities[ing.id] = it },
                                    label = { Text("Actual Quantity") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = ing.plannedUnit.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (inStock != null && inStock > 0.0) {
                                androidx.compose.material3.TextButton(
                                    onClick = { actualQuantities[ing.id] = com.example.chatgpt_mealplanner.core.util.formatQuantityNumber(inStock) }
                                ) { Text("Use all (${formatQuantityAndUnit(inStock, ing.plannedUnit)})") }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val overrides = ingredients.mapNotNull { ing ->
                                val qtyStr = actualQuantities[ing.id]
                                val qty = qtyStr?.toDoubleOrNull() ?: ing.plannedQuantity
                                ActualIngredientOverride(
                                    mealIngredientId = ing.id,
                                    actualQuantity = qty,
                                    actualUnit = ing.plannedUnit
                                )
                            }
                            val substitutions = ingredients.mapNotNull { ing ->
                                val item = picked[ing.id] ?: return@mapNotNull null
                                viewModel.rememberSameIngredient(ing.displayName, item.displayName)
                                com.example.chatgpt_mealplanner.core.model.IngredientSubstitution(
                                    mealIngredientId = ing.id,
                                    selectedInventoryItemId = item.id,
                                    actualQuantity = actualQuantities[ing.id]?.toDoubleOrNull() ?: ing.plannedQuantity,
                                    actualUnit = ing.plannedUnit
                                )
                            }
                            viewModel.onEvent(
                                MealPlannerUiEvent.ConfirmCookMeal(
                                    mealId = meal.id,
                                    actualIngredientOverrides = overrides,
                                    substitutions = substitutions,
                                    leftoverStorageLocation = null
                                )
                            )
                            onNavigateBack()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Confirm & Deduct Stock")
                    }
                }
            }
        }
    }
}
