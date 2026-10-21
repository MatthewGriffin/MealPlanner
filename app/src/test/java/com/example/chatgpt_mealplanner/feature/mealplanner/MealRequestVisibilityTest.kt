package com.example.chatgpt_mealplanner.feature.mealplanner

import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MealRequestVisibilityTest {
    @Test
    fun requestsForExistingMealIds_hidesRequestsForDeletedMeals() {
        val existingRequest = request(id = "request_existing", mealId = "meal_existing")
        val orphanedRequest = request(id = "request_orphaned", mealId = "meal_deleted")

        val visible = requestsForExistingMealIds(
            requests = listOf(existingRequest, orphanedRequest),
            existingMealIds = setOf("meal_existing")
        )

        assertEquals(listOf(existingRequest), visible)
    }

    @Test
    fun requestsForExistingMealIds_onlyShowsRequestsForSelectedDateMeals() {
        val selectedDayRequest = request(id = "request_selected_day", mealId = "meal_today")
        val otherDayRequest = request(id = "request_other_day", mealId = "meal_tomorrow")

        val visible = requestsForExistingMealIds(
            requests = listOf(selectedDayRequest, otherDayRequest),
            existingMealIds = setOf("meal_today")
        )

        assertEquals(listOf(selectedDayRequest), visible)
    }

    private fun request(id: String, mealId: String) = MealChangeRequestEntity(
        id = id,
        mealId = mealId,
        authorDeviceId = "follower_device",
        requestText = "Please change this meal",
        status = "PENDING",
        mainResponse = null,
        createdAt = 1L,
        updatedAt = 1L
    )
}
