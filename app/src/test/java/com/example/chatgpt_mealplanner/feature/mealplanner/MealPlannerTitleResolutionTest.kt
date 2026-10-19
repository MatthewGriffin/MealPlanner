package com.example.chatgpt_mealplanner.feature.mealplanner

import com.example.chatgpt_mealplanner.core.database.entity.MealEntity
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.repository.MealRepositoryImpl
import com.example.chatgpt_mealplanner.core.service.FakeMealDao
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MealPlannerTitleResolutionTest {

    @Test
    fun observeAllMealTitles_returnsMapOfMealIdToTitleOnMain() = runBlocking {
        val fakeMealDao = FakeMealDao()
        fakeMealDao.insertMeal(
            MealEntity(
                id = "meal_123",
                date = "2026-10-20",
                mealType = MealType.DINNER,
                title = "Chicken Tikka Masala",
                servings = 4,
                status = MealStatus.PLANNED,
                batchPortions = 0,
                immediatePortions = 4,
                leftoverPortions = 0,
                cookInTwoBatches = false,
                tags = emptySet(),
                instructions = null,
                notes = null,
                cookedAt = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        fakeMealDao.insertMeal(
            MealEntity(
                id = "meal_456",
                date = "2026-10-21",
                mealType = MealType.LUNCH,
                title = "Vegetable Lasagna",
                servings = 2,
                status = MealStatus.PLANNED,
                batchPortions = 0,
                immediatePortions = 2,
                leftoverPortions = 0,
                cookInTwoBatches = false,
                tags = emptySet(),
                instructions = null,
                notes = null,
                cookedAt = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        val fakePairingStore = FollowerPairingStore(null)
        val fakeRoleResolver = object : AppRoleResolver(fakePairingStore) {
            override fun isFollower(): Boolean = false
            override suspend fun isFollowerAsync(): Boolean = false
        }

        val repository = MealRepositoryImpl(
            mealDao = fakeMealDao,
            followerDatabase = mockFollowerDatabase(),
            roleResolver = fakeRoleResolver
        )

        val titlesMap = repository.observeAllMealTitles().first()

        assertEquals(2, titlesMap.size)
        assertEquals("Chicken Tikka Masala", titlesMap["meal_123"])
        assertEquals("Vegetable Lasagna", titlesMap["meal_456"])
        assertNull(titlesMap["meal_unknown"])
    }

    @Test
    fun mealTitleDisplayFallback_whenTitleMissingOrPresent() {
        val titleMap = mapOf("meal_123" to "Chicken Tikka Masala")

        val knownMealId = "meal_123"
        val unknownMealId = "meal_unknown_789"

        val resolvedTitle = titleMap[knownMealId]
        val resolvedLabel = if (!resolvedTitle.isNullOrBlank()) "Meal: $resolvedTitle" else "Meal ID: $knownMealId"
        assertEquals("Meal: Chicken Tikka Masala", resolvedLabel)

        val fallbackTitle = titleMap[unknownMealId]
        val fallbackLabel = if (!fallbackTitle.isNullOrBlank()) "Meal: $fallbackTitle" else "Meal ID: $unknownMealId"
        assertEquals("Meal ID: meal_unknown_789", fallbackLabel)
    }

    private fun mockFollowerDatabase(): com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase {
        return org.mockito.Mockito.mock(com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase::class.java)
    }
}
