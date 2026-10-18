package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.MealChangeRequestDao
import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealChangeRequestRepositoryImpl @Inject constructor(
    private val dao: MealChangeRequestDao,
    private val pairingStore: FollowerPairingStore
) : MealChangeRequestRepository {

    override fun observeRequestsForMeal(mealId: String): Flow<List<MealChangeRequestEntity>> {
        return dao.observeRequestsForMeal(mealId)
    }

    override fun observeAllRequests(): Flow<List<MealChangeRequestEntity>> {
        return dao.observeAllRequests()
    }

    override suspend fun submitRequest(mealId: String, requestText: String) {
        val requestId = "req_${UUID.randomUUID()}"
        val deviceId = pairingStore.getPairingConfig()?.deviceId ?: "main_device"
        val now = System.currentTimeMillis()
        val entity = MealChangeRequestEntity(
            id = requestId,
            mealId = mealId,
            authorDeviceId = deviceId,
            requestText = requestText,
            status = "PENDING",
            mainResponse = null,
            createdAt = now,
            updatedAt = now
        )
        dao.insertRequest(entity)
    }

    override suspend fun updateRequestStatus(requestId: String, status: String, mainResponse: String?) {
        val now = System.currentTimeMillis()
        dao.updateRequestStatus(requestId, status, mainResponse, now)
    }
}
