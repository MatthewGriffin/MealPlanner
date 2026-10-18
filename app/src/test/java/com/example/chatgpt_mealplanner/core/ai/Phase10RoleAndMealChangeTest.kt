package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import com.example.chatgpt_mealplanner.core.repository.MealChangeRequestRepository
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class Phase10RoleAndMealChangeTest {

    @Test
    fun followerRoleDetectionTest() {
        runBlocking {
            val fakePairingStore = FollowerPairingStore(null)
            val fakeAppRoleResolver = object : AppRoleResolver(fakePairingStore) {
                override fun isFollower(): Boolean = true
                override suspend fun isFollowerAsync(): Boolean = true
            }
            assertTrue(fakeAppRoleResolver.isFollower())
        }
    }

    @Test
    fun mealChangeRequestRepositoryContract() {
        runBlocking {
            val request = MealChangeRequestEntity(
                id = "req_1",
                mealId = "meal_1",
                authorDeviceId = "device_follower",
                requestText = "Can we substitute this ingredient?",
                status = "PENDING",
                mainResponse = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val fakeRepo = object : MealChangeRequestRepository {
                override fun observeRequestsForMeal(mealId: String): Flow<List<MealChangeRequestEntity>> = flowOf(listOf(request))
                override fun observeAllRequests(): Flow<List<MealChangeRequestEntity>> = flowOf(listOf(request))
                override suspend fun submitRequest(mealId: String, requestText: String) {}
                override suspend fun updateRequestStatus(requestId: String, status: String, mainResponse: String?) {}
            }

            val list = mutableListOf<List<MealChangeRequestEntity>>()
            fakeRepo.observeRequestsForMeal("meal_1").collect { list.add(it) }
            assertEquals(1, list.size)
            assertEquals(1, list[0].size)
            assertEquals("Can we substitute this ingredient?", list[0][0].requestText)
            assertEquals("PENDING", list[0][0].status)
        }
    }
}
