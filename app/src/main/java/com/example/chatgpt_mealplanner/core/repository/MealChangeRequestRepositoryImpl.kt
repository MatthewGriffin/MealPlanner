package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.MealChangeRequestDao
import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import kotlinx.coroutines.flow.Flow
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.sync.contract.SYNC_PROTOCOL_VERSION
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import com.example.chatgpt_mealplanner.sync.contract.UpdateMealChangeRequestStatusPayloadDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealChangeRequestRepositoryImpl @Inject constructor(
    private val dao: MealChangeRequestDao,
    private val pairingStore: FollowerPairingStore,
    private val appRoleResolver: AppRoleResolver,
    private val canonicalSyncDao: CanonicalSyncDao,
    private val transactionRunner: DatabaseTransactionRunner
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
        transactionRunner.runInTransaction {
            val existing = dao.getRequestById(requestId)
                ?: throw IllegalArgumentException("Meal request not found")
            check(existing.status == "PENDING" || existing.status == "DISCUSSED") {
                "This meal request has already been decided"
            }
            val now = System.currentTimeMillis()
            val sequence = canonicalSyncDao.getSequenceEntity()
                ?: CanonicalSyncSequenceEntity(singletonId = 1, nextSequence = 1L)
            val next = sequence.nextSequence
            canonicalSyncDao.updateSequenceEntity(sequence.copy(nextSequence = next + 1L))
            canonicalSyncDao.insertCanonicalOperation(
                CanonicalSyncOperationEntity(
                    operationId = UUID.randomUUID().toString(),
                    deviceId = "main_device",
                    deviceSequence = next,
                    protocolVersion = SYNC_PROTOCOL_VERSION,
                    operationType = SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS.name,
                    payloadJson = Json.encodeToString(
                        UpdateMealChangeRequestStatusPayloadDto(requestId, status, mainResponse)
                    ),
                    createdAt = now,
                    canonicalSequence = next,
                    receivedAt = now,
                    status = SyncOperationStatus.APPLIED.name,
                    resultCode = "SUCCESS"
                )
            )
            dao.updateRequestStatus(requestId, status, mainResponse, now)
        }
    }
}
