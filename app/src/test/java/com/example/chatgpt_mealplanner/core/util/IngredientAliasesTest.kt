package com.example.chatgpt_mealplanner.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class IngredientAliasesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() { context.getSharedPreferences("ingredient_aliases", Context.MODE_PRIVATE).edit().clear().commit(); IngredientNameMatcher.aliases = emptyMap() }
    @After fun reset() { context.getSharedPreferences("ingredient_aliases", Context.MODE_PRIVATE).edit().clear().commit(); IngredientNameMatcher.aliases = emptyMap() }

    @Test fun aPairTheHouseholdTeachesMatchesBothWaysRound() {
        assertFalse(IngredientNameMatcher.matches("Brioche rolls", "Briosh burger buns"))
        val aliases = IngredientAliases(context)
        assertTrue(aliases.add("Briosh burger buns", "Brioche rolls"))
        assertTrue(IngredientNameMatcher.matches("Brioche rolls", "Briosh burger buns"))
        assertTrue(IngredientNameMatcher.matches("Briosh burger buns", "Brioche rolls"))
        // Only those two names: nothing else starts matching.
        assertFalse(IngredientNameMatcher.matches("Brioche rolls", "Hot dog buns"))
    }

    @Test fun pairsSurviveARestartAndTravelToAFollower() {
        IngredientAliases(context).add("Free range chicken thighs", "Chicken legs")
        IngredientNameMatcher.aliases = emptyMap()
        val reloaded = IngredientAliases(context)
        assertTrue(IngredientNameMatcher.matches("Chicken legs", "Free range chicken thighs"))

        context.getSharedPreferences("ingredient_aliases", Context.MODE_PRIVATE).edit().clear().commit()
        IngredientNameMatcher.aliases = emptyMap()
        val follower = IngredientAliases(context)
        follower.install(reloaded.snapshot())
        assertTrue(IngredientNameMatcher.matches("Chicken legs", "Free range chicken thighs"))
    }

    @Test fun namesThatAreAlreadyTheSameThingAreNotPaired() {
        assertFalse(IngredientAliases(context).add("Mild cheddar cheese", "Cheddar cheese"))
        assertFalse(IngredientAliases(context).add("", "Milk"))
    }
}
