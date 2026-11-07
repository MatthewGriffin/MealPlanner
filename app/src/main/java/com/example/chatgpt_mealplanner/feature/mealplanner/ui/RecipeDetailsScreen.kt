package com.example.chatgpt_mealplanner.feature.mealplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.MealRating
import com.example.chatgpt_mealplanner.core.util.formatQuantityAndUnit
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerUiEvent
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerViewModel

@Composable
internal fun MealRatingChips(rating: MealRating?, onRate: (MealRating?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = rating == MealRating.UP,
            onClick = { onRate(MealRating.UP.takeUnless { rating == it }) },
            label = { Text("👍 Liked it") }
        )
        FilterChip(
            selected = rating == MealRating.DOWN,
            onClick = { onRate(MealRating.DOWN.takeUnless { rating == it }) },
            label = { Text("👎 Not for us") }
        )
    }
}

@Composable
fun RecipeDetailsScreen(
    plannedMealId: String,
    viewModel: MealPlannerViewModel,
    onNavigateBack: () -> Unit,
    onCookNowClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAdjustWithAiRequest: (com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val allMeals = uiState.mealsForSelectedDate + uiState.recentCookedMeals
    val mealWithIngredients = allMeals.firstOrNull { it.meal.id == plannedMealId }

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
                text = "Recipe Details",
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
                    text = "Recipe or meal not found",
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = meal.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${meal.mealType.name} • ${meal.servings} servings",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Status: ${meal.status.name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // A follower's meals come from the main phone, which holds the ratings.
                    if (!uiState.isFollower) {
                        Spacer(modifier = Modifier.height(8.dp))
                        MealRatingChips(
                            rating = meal.rating,
                            onRate = { viewModel.onEvent(MealPlannerUiEvent.RateMeal(meal.id, it)) }
                        )
                    }
                }

                item {
                    Text(
                        text = "Ingredients",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(ingredients, key = { it.id }) { ing ->
                    val qtyFormatted = formatQuantityAndUnit(ing.plannedQuantity, ing.plannedUnit)
                    Text(
                        text = "• ${ing.displayName} — $qtyFormatted",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Method",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!meal.instructions.isNullOrBlank()) {
                        Text(
                            text = meal.instructions,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            text = "Cooking instructions aren't available for this planned meal.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onCookNowClicked(meal.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cook Now")
                    }
                }

                val mealRequests = uiState.allChangeRequests.filter { it.mealId == meal.id }
                if (uiState.isFollower) {
                    if (mealRequests.isNotEmpty()) {
                        item(key = "request_history") {
                            FollowerRequestStatusSection(mealRequests)
                        }
                    }
                    item(key = "request_composer") {
                        var requestInput by rememberSaveable(meal.id) { mutableStateOf("") }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Request a meal change", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            MealRequestTextField(
                                value = requestInput,
                                onValueChange = { if (it.length <= 2000) requestInput = it },
                                label = "Suggest substitution / change request...")
                            Button(
                                onClick = {
                                    viewModel.submitMealChangeRequest(meal.id, requestInput.trim())
                                    requestInput = ""
                                },
                                enabled = requestInput.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Submit request") }
                        }
                    }
                } else if (mealRequests.isNotEmpty()) {
                    item(key = "request_review") {
                        MainRequestReviewSection(
                            requests = mealRequests,
                            onUpdateStatus = viewModel::updateRequestStatus,
                            mealTitleMap = mapOf(meal.id to meal.title),
                            onAdjustWithAi = onAdjustWithAiRequest
                        )
                    }
                }
            }
        }
    }
}
