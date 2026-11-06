package com.example.chatgpt_mealplanner.feature.settings

import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Test

class CookingPreferencesUiStateTest {

    @Test
    fun typedLists_areTrimmedAndDeduplicated() {
        val preferences = CookingPreferencesUiState(
            allergies = setOf("Milk"),
            otherAllergies = " kiwi, , latex ,kiwi",
            dislikes = "coriander,  olives",
            equipment = "air fryer"
        ).toPreferences()

        assertEquals(listOf("Milk", "kiwi", "latex"), preferences.allergies)
        assertEquals(listOf("coriander", "olives"), preferences.dislikes)
        assertEquals(listOf("air fryer"), preferences.equipment)
    }

    @Test
    fun savedPreferences_loadBackUnchanged() {
        val saved = UserPreferencesSummaryDto(
            householdSize = 3,
            allergies = listOf("Milk", "kiwi"),
            diets = listOf("Vegan"),
            dislikes = listOf("olives"),
            equipment = listOf("air fryer"),
            skillLevel = "Beginner"
        )

        val state = CookingPreferencesUiState.from(saved)

        assertEquals(setOf("Milk"), state.allergies)
        assertEquals("kiwi", state.otherAllergies)
        assertEquals(saved, state.toPreferences())
    }
}
