package com.example.chatgpt_mealplanner.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

            MultiChoiceDropdown("Allergies", DietaryRules.ALLERGIES, state.allergies) { allergy ->
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

            MultiChoiceDropdown("Diet", DietaryRules.DIETS, state.diets) { diet ->
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

            SkillDropdown(state.skillLevel) { level -> onChange { it.copy(skillLevel = level) } }
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

/** Stays open after each tick, because several answers are normal here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiChoiceDropdown(label: String, options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    DropdownField(
        label = label,
        value = selected.sortedBy { options.indexOf(it) }.joinToString(", ").ifEmpty { "None" },
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option) },
                leadingIcon = { Checkbox(checked = option in selected, onCheckedChange = null) },
                onClick = { onToggle(option) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillDropdown(selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    DropdownField(
        label = "Cooking skill",
        value = selected ?: NOT_SET,
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        (listOf(null) + CookingPreferencesUiState.SKILL_LEVELS).forEach { level ->
            DropdownMenuItem(
                text = { Text(level ?: NOT_SET) },
                onClick = {
                    onSelect(level)
                    expanded = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: @Composable ColumnScope.() -> Unit
) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }, content = items)
    }
}

private const val NOT_SET = "Not set"

private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value
