package com.example.chatgpt_mealplanner.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.chatgpt_mealplanner.core.ai.validation.DietaryRules

@Composable
fun CookingPreferencesSection(viewModel: CookingPreferencesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    CookingPreferencesCard(state = state, onChange = viewModel::update, onSave = viewModel::save)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CookingPreferencesCard(
    state: CookingPreferencesUiState,
    onChange: ((CookingPreferencesUiState) -> CookingPreferencesUiState) -> Unit,
    onSave: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Cooking Preferences", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Label("Allergies")
            ChoiceChips(DietaryRules.ALLERGIES, state.allergies) { allergy ->
                onChange { it.copy(allergies = it.allergies.toggled(allergy)) }
            }
            Field("Other allergies, separated by commas", state.otherAllergies) { text ->
                onChange { it.copy(otherAllergies = text) }
            }
            Text(
                "The app checks ingredient names, but it can't see inside products or guarantee a recipe is safe. Always check labels.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))

            Label("Diet")
            ChoiceChips(DietaryRules.DIETS, state.diets) { diet ->
                onChange { it.copy(diets = it.diets.toggled(diet)) }
            }
            Spacer(Modifier.height(4.dp))
            Field("Foods you dislike, separated by commas", state.dislikes) { text ->
                onChange { it.copy(dislikes = text) }
            }
            Spacer(Modifier.height(12.dp))

            Label("People to cook for")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onChange { it.copy(householdSize = it.householdSize - 1) } },
                    enabled = state.householdSize > CookingPreferencesUiState.MIN_HOUSEHOLD
                ) { Text("−") }
                Text(state.householdSize.toString(), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { onChange { it.copy(householdSize = it.householdSize + 1) } },
                    enabled = state.householdSize < CookingPreferencesUiState.MAX_HOUSEHOLD
                ) { Text("+") }
            }
            Spacer(Modifier.height(12.dp))

            Label("Cooking skill")
            ChoiceChips(CookingPreferencesUiState.SKILL_LEVELS, setOfNotNull(state.skillLevel)) { level ->
                onChange { it.copy(skillLevel = level.takeUnless { _ -> it.skillLevel == level }) }
            }
            Spacer(Modifier.height(4.dp))
            Field("Special equipment, such as air fryer, separated by commas", state.equipment) { text ->
                onChange { it.copy(equipment = text) }
            }
            Spacer(Modifier.height(12.dp))

            Button(onClick = onSave, modifier = Modifier.align(Alignment.End)) { Text("Save preferences") }
            state.message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Field(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onToggle(option) },
                label = { Text(option) }
            )
        }
    }
}

private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value
