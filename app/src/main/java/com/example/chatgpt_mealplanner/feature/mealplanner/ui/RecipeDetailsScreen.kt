package com.example.chatgpt_mealplanner.feature.mealplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import com.example.chatgpt_mealplanner.core.util.RecipeTextParser
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
private fun InfoPill(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            content()
        }
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

            val parsed = remember(meal.instructions) { RecipeTextParser.parse(meal.instructions.orEmpty()) }
            val totalMinutes = listOfNotNull(parsed.prepMinutes, parsed.cookMinutes).sum().takeIf { it > 0 }
            val linkColor = MaterialTheme.colorScheme.primary

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = meal.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${meal.mealType.name.lowercase().replaceFirstChar { it.uppercase() }} \u2022 ${meal.status.name.lowercase().replaceFirstChar { it.uppercase() }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                InfoPill("${meal.servings} servings")
                                parsed.prepMinutes?.let { InfoPill("Prep $it min") }
                                parsed.cookMinutes?.let { InfoPill("Cook $it min") }
                                if (parsed.prepMinutes == null && parsed.cookMinutes == null) totalMinutes?.let { InfoPill("$it min") }
                            }
                            // A follower's meals come from the main phone, which holds the ratings.
                            if (!uiState.isFollower) {
                                MealRatingChips(
                                    rating = meal.rating,
                                    onRate = { viewModel.onEvent(MealPlannerUiEvent.RateMeal(meal.id, it)) }
                                )
                            }
                        }
                    }
                }

                if (parsed.equipment.isNotEmpty()) {
                    item {
                        Text(
                            text = "You'll need: " + parsed.equipment.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    SectionCard("Ingredients") {
                        ingredients.forEachIndexed { index, ing ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ing.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    formatQuantityAndUnit(ing.plannedQuantity, ing.plannedUnit),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                if (meal.instructions.isNullOrBlank()) {
                    item {
                        SectionCard("Method") {
                            Text(
                                "Cooking instructions aren't available for this planned meal.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    item {
                        SectionCard("Method") {
                            if (parsed.steps.isEmpty()) {
                                Text(
                                    text = com.example.chatgpt_mealplanner.ui.text.withTappableLinks(meal.instructions, linkColor),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            parsed.steps.forEach { step ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            step.number.toString(),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(step.text, style = MaterialTheme.typography.bodyLarge)
                                        val timers = remember(step.text, step.minutes) { com.example.chatgpt_mealplanner.core.util.StepTimers.forStep(step.text, step.minutes) }
                                        val timerContext = androidx.compose.ui.platform.LocalContext.current
                                        if (timers.isNotEmpty()) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                timers.forEach { timer ->
                                                    androidx.compose.material3.AssistChip(
                                                        onClick = {
                                                            com.example.chatgpt_mealplanner.core.util.StepTimerLauncher.start(timerContext, timer, "Step ${step.number}: ${step.text.trim().take(40)}")
                                                        },
                                                        label = { Text(timer.label) },
                                                        leadingIcon = { Text("\u25B6") }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            parsed.other.forEach {
                                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }

                parsed.nutrition?.let { facts ->
                    item {
                        SectionCard("Nutrition per portion (estimate)") {
                            Text(facts, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (parsed.swaps.isNotEmpty() || parsed.worthAdding.isNotEmpty()) {
                    item {
                        SectionCard("Tips") {
                            parsed.swaps.forEach {
                                Text("\uD83D\uDD01 $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            if (parsed.worthAdding.isNotEmpty()) {
                                Text(
                                    "Worth adding (not on your shopping list)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                )
                                parsed.worthAdding.forEach {
                                    Text("\u2022 $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    }
                }

                parsed.basedOn?.let { source ->
                    item {
                        Text(
                            text = com.example.chatgpt_mealplanner.ui.text.withTappableLinks("Based on: $source", linkColor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    if (meal.status == com.example.chatgpt_mealplanner.core.model.MealStatus.COOKED) {
                        Text(
                            "Cooked \u2713",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )
                    } else {
                        Button(
                            onClick = { onCookNowClicked(meal.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cook Now")
                        }
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
