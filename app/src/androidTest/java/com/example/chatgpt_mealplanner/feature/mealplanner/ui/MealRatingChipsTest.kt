package com.example.chatgpt_mealplanner.feature.mealplanner.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.model.MealRating
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealRatingChipsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingARating_selectsIt_switchesIt_andTappingAgainClearsIt() {
        var rating by mutableStateOf<MealRating?>(null)
        composeRule.setContent { MealRatingChips(rating = rating, onRate = { rating = it }) }
        val liked = composeRule.onNodeWithText("👍 Liked it")
        val notForUs = composeRule.onNodeWithText("👎 Not for us")

        liked.assertIsNotSelected()
        notForUs.assertIsNotSelected()

        liked.performClick()
        liked.assertIsSelected()
        notForUs.assertIsNotSelected()

        notForUs.performClick()
        liked.assertIsNotSelected()
        notForUs.assertIsSelected()

        notForUs.performClick()
        notForUs.assertIsNotSelected()
    }
}
