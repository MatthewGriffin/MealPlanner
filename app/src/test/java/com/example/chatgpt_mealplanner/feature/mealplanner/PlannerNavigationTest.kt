package com.example.chatgpt_mealplanner.feature.mealplanner

import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerTab
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PlannerNavigationTest {

    @Test
    fun plannerOverview_definesAllThreeTabs() {
        val tabs = MealPlannerTab.entries.toTypedArray()
        assertEquals(3, tabs.size)
        assertEquals("Meal Plan", tabs[0].displayName)
        assertEquals("Leftovers", tabs[1].displayName)
        assertEquals("History", tabs[2].displayName)
    }

    @Test
    fun plannerState_defaultsToPlannerTab() {
        val state = MealPlannerUiState()
        assertEquals(MealPlannerTab.PLANNER, state.activeTab)
    }

    @Test
    fun plannerState_tabSelectionUpdatesActiveTab() {
        val state = MealPlannerUiState(activeTab = MealPlannerTab.LEFTOVERS)
        assertEquals(MealPlannerTab.LEFTOVERS, state.activeTab)
    }
}
