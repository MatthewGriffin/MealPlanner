package com.example.chatgpt_mealplanner.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookingPreferencesCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var latest = CookingPreferencesUiState()

    private fun show(initial: CookingPreferencesUiState = CookingPreferencesUiState()) {
        composeRule.setContent {
            var state by remember { mutableStateOf(initial) }
            latest = state
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CookingPreferencesCard(state = state, onChange = { state = it(state) }, onSave = {})
            }
        }
    }

    private fun tap(text: String) = composeRule.onNodeWithText(text).performScrollTo().performClick()

    @Test
    fun choosingAllergyDietAndSkill_updatesTheAnswers() {
        show()

        tap("Peanuts")
        tap("Vegan")
        tap("Beginner")
        tap("+")

        composeRule.onNodeWithText("Peanuts").assertIsSelected()
        composeRule.waitForIdle()
        assertEquals(setOf("Peanuts"), latest.allergies)
        assertEquals(setOf("Vegan"), latest.diets)
        assertEquals("Beginner", latest.skillLevel)
        assertEquals(3, latest.householdSize)
    }

    @Test
    fun tappingAnAllergyAgain_removesIt() {
        show(CookingPreferencesUiState(allergies = setOf("Milk")))

        tap("Milk")

        composeRule.waitForIdle()
        assertEquals(emptySet<String>(), latest.allergies)
    }

    @Test
    fun householdSize_staysWithinItsLimits() {
        show(CookingPreferencesUiState(householdSize = 1))

        composeRule.onNodeWithText("−").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun warnsThatLabelsStillNeedChecking() {
        show()

        composeRule.onNodeWithText("Always check labels", substring = true).performScrollTo().assertExists()
    }
}
