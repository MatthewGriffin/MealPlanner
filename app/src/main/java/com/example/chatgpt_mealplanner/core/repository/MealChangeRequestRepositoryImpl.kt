package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.MealChangeRequestDao
import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealChangeRequestRepositoryImpl @Inject constructor(
    private val dao: MealChangeRequestDao,
    private val pairingStore: FollowerPairingStore,
    private val appRoleResolver: AppRoleResolver
) : MealChangeRequestRepository {

    override fun observeRequestsForMeal(mealId: String): Flow<List<MealChangeRequestEntity>> {
        return dao.observeRequestsForMeal(mealId)
    }

    override fun observeAllRequests(): Flow<List<MealChangeRequestEntity>> {
        return dao.observeAllRequests()
    }

    override suspend fun submitRequest(mealId: String, requestText: String) {
        require(mealId.isNotBlank() && mealId.length <= 128) { "Invalid meal ID" }
        require(requestText.isNotBlank() && requestText.length <= 2000) { "Request must contain 1 to 2000 characters" }
        // Until meal projections and status feed are implemented, never present a
        // follower-local Room insert as a successfully synchronized request.
        check(!appRoleResolver.isFollowerAsync()) {
            "Follower meal requests are not available until meal synchronization is enabled"
        }
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
        check(appRoleResolver.isMainAsync()) { "Only Main can decide meal-change requests" }
        require(status in setOf("ACCEPTED", "DECLINED", "DISCUSSED")) { "Invalid request status" }
        require(mainResponse == null || mainResponse.length <= 2000) { "Response exceeds 2000 characters" }
        val existing = dao.getRequestById(requestId)
            ?: throw IllegalArgumentException("Meal request not found")
        check(existing.status == "PENDING" || existing.status == "DISCUSSED") {
            "This meal request has already been decided"
        }
        val now = System.currentTimeMillis()
        dao.updateRequestStatus(requestId, status, mainResponse, now)
    }
}
