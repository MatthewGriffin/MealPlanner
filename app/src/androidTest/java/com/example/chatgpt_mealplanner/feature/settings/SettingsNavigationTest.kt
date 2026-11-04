package com.example.chatgpt_mealplanner.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun clickGearIcon_navigatesToSettingsScreen_andDisplaysGeminiApiKey() {
        // 1. Assert gear icon exists in top-right TopAppBar
        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()

        // 2. Perform click on gear icon
        composeTestRule.onNodeWithContentDescription("Settings").performClick()

        // 3. Assert "Gemini API Key" card title is visible on screen
        composeTestRule.onNodeWithText("Gemini API Key").assertIsDisplayed()
    }
}
