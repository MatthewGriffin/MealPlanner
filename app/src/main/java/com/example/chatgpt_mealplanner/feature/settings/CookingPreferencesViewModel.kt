package com.example.chatgpt_mealplanner.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.ai.validation.DietaryRules
import com.example.chatgpt_mealplanner.core.repository.CookingPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CookingPreferencesUiState(
    val allergies: Set<String> = emptySet(),
    val otherAllergies: String = "",
    val diets: Set<String> = emptySet(),
    val dislikes: String = "",
    val equipment: String = "",
    val householdSize: Int = 2,
    val skillLevel: String? = null,
    val message: String? = null
) {
    fun toPreferences() = UserPreferencesSummaryDto(
        householdSize = householdSize,
        allergies = allergies.toList() + commaSeparated(otherAllergies),
        diets = diets.toList(),
        dislikes = commaSeparated(dislikes),
        equipment = commaSeparated(equipment),
        skillLevel = skillLevel
    )

    companion object {
        const val MIN_HOUSEHOLD = 1
        const val MAX_HOUSEHOLD = 12
        val SKILL_LEVELS = listOf("Beginner", "Confident", "Experienced")

        fun from(preferences: UserPreferencesSummaryDto) = CookingPreferencesUiState(
            allergies = preferences.allergies.filter { it in DietaryRules.ALLERGIES }.toSet(),
            otherAllergies = preferences.allergies.filterNot { it in DietaryRules.ALLERGIES }.joinToString(", "),
            diets = preferences.diets.toSet(),
            dislikes = preferences.dislikes.joinToString(", "),
            equipment = preferences.equipment.joinToString(", "),
            householdSize = preferences.householdSize,
            skillLevel = preferences.skillLevel
        )

        private fun commaSeparated(text: String): List<String> =
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

@HiltViewModel
class CookingPreferencesViewModel @Inject constructor(
    private val store: CookingPreferencesStore
) : ViewModel() {

    private val state = MutableStateFlow(CookingPreferencesUiState())
    val uiState: StateFlow<CookingPreferencesUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { state.value = CookingPreferencesUiState.from(store.current()) }
    }

    fun update(change: (CookingPreferencesUiState) -> CookingPreferencesUiState) {
        state.update { change(it).copy(message = null) }
    }

    fun save() {
        viewModelScope.launch {
            val result = runCatching { store.save(state.value.toPreferences()) }
            state.update {
                it.copy(message = if (result.isSuccess) "Saved. New recipes will follow these." else "Couldn't save these. Please try again.")
            }
        }
    }
}
