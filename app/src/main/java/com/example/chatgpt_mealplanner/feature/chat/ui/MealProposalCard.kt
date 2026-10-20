package com.example.chatgpt_mealplanner.feature.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.contract.IngredientAvailability
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ValidatedMealProposal

@Composable
fun MealProposalCard(
    validatedProposal: ValidatedMealProposal,
    onSaveToPlan: (MealProposal) -> Unit,
    isSaving: Boolean = false,
    modifier: Modifier = Modifier
) {
    val proposal = validatedProposal.proposal

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Proposed Meal: ${proposal.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Servings: ${proposal.servings} • Est. Time: ${proposal.estimatedMinutes ?: 20} mins",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Reasoning: ${proposal.reasoningSummary}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Ingredients Pantry Status:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))

            validatedProposal.validatedIngredients.forEach { valIng ->
                val badge = when (valIng.availability) {
                    IngredientAvailability.AVAILABLE -> "[In Stock]"
                    IngredientAvailability.PARTIAL -> "[Partial]"
                    IngredientAvailability.MISSING -> "[Shopping Needed]"
                    IngredientAvailability.UNCERTAIN -> "[Uncertain]"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "• ${valIng.ingredient.displayName} (${valIng.ingredient.quantity} ${valIng.ingredient.unit})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = when (valIng.availability) {
                            IngredientAvailability.AVAILABLE -> MaterialTheme.colorScheme.primary
                            IngredientAvailability.MISSING -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        }
                    )
                }
            }

            if (validatedProposal.warnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                validatedProposal.warnings.forEach { warning ->
                    Text(
                        text = "Warning: $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onSaveToPlan(proposal) },
                enabled = !isSaving,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(if (isSaving) "Saving…" else "Save to Plan")
            }
        }
    }
}
