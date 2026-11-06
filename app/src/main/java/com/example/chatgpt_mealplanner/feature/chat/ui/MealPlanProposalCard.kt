package com.example.chatgpt_mealplanner.feature.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ValidatedMealProposal
import com.example.chatgpt_mealplanner.contract.IngredientAvailability

@Composable
fun MealPlanProposalCard(
    validatedMeals: List<ValidatedMealProposal>,
    onAccept: (List<MealProposal>) -> Unit,
    isSaving: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Proposed Meal Plan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${validatedMeals.size} meals • Review the week before accepting",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(12.dp))
            validatedMeals.sortedWith(compareBy<ValidatedMealProposal> { it.proposal.suggestedDate ?: "" }.thenBy { it.proposal.mealType ?: "" }).forEach { validated ->
                val meal = validated.proposal
                val date = meal.suggestedDate.orEmpty()
                val type = meal.mealType.orEmpty().lowercase().replaceFirstChar { it.uppercase() }
                val required = validated.validatedIngredients.filterNot { it.ingredient.optional }
                val owned = required.count { it.availability == IngredientAvailability.AVAILABLE }
                val toBuy = required.count { it.availability == IngredientAvailability.PARTIAL || it.availability == IngredientAvailability.MISSING }
                val toCheck = required.count { it.availability == IngredientAvailability.UNCERTAIN }
                Text(
                    text = listOf(date, type, meal.title).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "${meal.ingredients.size} ingredients • ${meal.steps.size} cooking steps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text("$owned owned • $toBuy to buy${if (toCheck > 0) " • $toCheck to check" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                validated.issues.forEach { issue ->
                    Text(
                        text = "Can't save yet: $issue",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = "Accepting adds all meals to your planner. Shopping requirements update automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Button(onClick = { onAccept(validatedMeals.map { it.proposal }) }, enabled = !isSaving && validatedMeals.all { it.canBeSaved() }) {
                    Text(if (isSaving) "Saving…" else "Accept meal plan")
                }
            }
        }
    }
}
