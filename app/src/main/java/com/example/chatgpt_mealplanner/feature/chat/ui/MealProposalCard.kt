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

            val slot = com.example.chatgpt_mealplanner.core.ai.resolver.MealSlotResolver.resolveSlot(
                requestedDateStr = proposal.suggestedDate,
                requestedMealTypeStr = proposal.mealType
            )
            val slotDate = runCatching { java.time.LocalDate.parse(slot.date) }.getOrNull()
            Text(
                text = "Will be saved for " +
                    (slotDate?.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault())) ?: slot.date) +
                    " \u2022 " + slot.mealType.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Servings: ${proposal.servings}" + (proposal.totalMinutes()?.let { " • About $it mins" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (proposal.batchPortions > proposal.servings) {
                val extra = proposal.batchPortions - proposal.servings
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Makes ${proposal.batchPortions} portions: ${proposal.servings} now, $extra extra. " +
                        "Extra portions go on later days of your plan if they fit, otherwise they are frozen.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            proposal.changeSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = "What changed: $summary",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            val requiredIngredients = validatedProposal.validatedIngredients.filterNot { it.ingredient.optional }
            val ownedCount = requiredIngredients.count { it.availability == IngredientAvailability.AVAILABLE }
            val toBuyCount = requiredIngredients.count { it.availability == IngredientAvailability.PARTIAL || it.availability == IngredientAvailability.MISSING }
            val toCheckCount = requiredIngredients.count { it.availability == IngredientAvailability.UNCERTAIN }
            Text(
                text = "$ownedCount owned • $toBuyCount to buy" + if (toCheckCount > 0) " • $toCheckCount to check" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))

            proposal.reasoningSummary.takeIf { it.isNotBlank() }?.let { why ->
                Text(
                    text = "Why this suits you: $why",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            proposal.source?.takeIf { it.isNotBlank() }?.let { source ->
                Text(
                    text = com.example.chatgpt_mealplanner.ui.text.withTappableLinks("Based on: $source", MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

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
                        text = valIng.ingredient.cardLine(),
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

            if (proposal.equipment.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "You'll need: ${proposal.equipment.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            if (proposal.worthAdding.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Worth adding (not on your shopping list):",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                proposal.worthAdding.forEach { idea ->
                    Text(
                        text = "• $idea",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (proposal.steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Method:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                proposal.methodLines().forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            if (validatedProposal.issues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                validatedProposal.issues.forEach { issue ->
                    Text(
                        text = "Can't save yet: $issue",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
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
                enabled = !isSaving && validatedProposal.canBeSaved(),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(if (isSaving) "Saving…" else "Save to Plan")
            }
        }
    }
}

private fun com.example.chatgpt_mealplanner.contract.ProposedIngredient.cardLine(): String {
    val amount = listOfNotNull(measure, "$quantity ${unit.lowercase()}").joinToString(", ")
    val optionalNote = if (optional) ", optional" else ""
    val swap = substitute?.let { " or $it" }.orEmpty()
    return "• $displayName ($amount$optionalNote)$swap"
}
