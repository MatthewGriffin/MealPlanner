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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun openOrClose(label: String) {
        composeRule.onNodeWithText(label).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    /** An option in the open menu, not the field behind it, which can show the same text. */
    private fun tap(text: String) {
        composeRule.onNode(hasText(text) and hasAnyAncestor(isPopup())).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun optionsAreHiddenUntilTheDropdownIsOpened() {
        show()

        composeRule.onNodeWithText("Peanuts").assertDoesNotExist()
        composeRule.onNodeWithText("Vegan").assertDoesNotExist()
        composeRule.onNodeWithText("Beginner").assertDoesNotExist()
    }

    @Test
    fun choosingAllergyDietAndSkill_updatesTheAnswers() {
        show()

        openOrClose("Allergies")
        tap("Peanuts")
        tap("Milk")
        openOrClose("Allergies")
        openOrClose("Diet")
        tap("Vegan")
        openOrClose("Diet")
        openOrClose("Cooking skill")
        tap("Beginner")
        composeRule.onNodeWithText("+").performScrollTo().performClick()

        composeRule.waitForIdle()
        assertEquals(setOf("Peanuts", "Milk"), latest.allergies)
        assertEquals(setOf("Vegan"), latest.diets)
        assertEquals("Beginner", latest.skillLevel)
        assertEquals(3, latest.householdSize)
    }

    @Test
    fun chosenAllergiesAreShownWithTheDropdownClosed() {
        show(CookingPreferencesUiState(allergies = setOf("Milk", "Peanuts")))

        composeRule.onNodeWithText("Milk", substring = true).performScrollTo().assertExists()
        composeRule.onNodeWithText("Peanuts", substring = true).assertExists()
    }

    @Test
    fun tappingAnAllergyAgain_removesIt() {
        show(CookingPreferencesUiState(allergies = setOf("Milk")))

        openOrClose("Allergies")
        tap("Milk")

        assertEquals(emptySet<String>(), latest.allergies)
    }

    @Test
    fun skillCanBeClearedAgain() {
        show(CookingPreferencesUiState(skillLevel = "Beginner"))

        openOrClose("Cooking skill")
        tap("Not set")

        assertNull(latest.skillLevel)
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
