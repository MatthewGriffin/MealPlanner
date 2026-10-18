package com.example.chatgpt_mealplanner.core.sync.follower.engine

import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerMealRequestEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.toFollowerEntity
import com.example.chatgpt_mealplanner.sync.contract.*
import kotlinx.serialization.json.Json

// Call inside the FollowerDatabase transaction that advances the cursor.
object FollowerMealRequestProjector {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun apply(dao: FollowerMealRequestDao, event: SyncFeedItemDto) {
        if (event.status != SyncOperationStatus.APPLIED) return
        require(event.canonicalSequence > 0)
        val candidate = when (event.operationType) {
            SyncOperationType.CREATE_MEAL_CHANGE_REQUEST -> {
                val p = json.decodeFromString<CreateMealChangeRequestPayloadDto>(event.payloadJson)
                val snapshot = MealChangeRequestSyncDto(
                    p.requestId, p.mealId, event.sourceDeviceId, p.requestText,
                    "PENDING", null, event.occurredAt, event.occurredAt
                )
                require(MealRequestSyncValidation.isValid(snapshot)) { "Invalid meal request event" }
                snapshot.toFollowerEntity(event.canonicalSequence)
            }
            SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS -> {
                val p = json.decodeFromString<UpdateMealChangeRequestStatusPayloadDto>(event.payloadJson)
                require(p.status in setOf("ACCEPTED", "DECLINED", "DISCUSSED"))
                require((p.mainResponse?.length ?: 0) <= 2000)
                val existing = dao.getById(p.requestId)
                val snapshot = p.requestSnapshot
                if (snapshot != null) {
                    require(MealRequestSyncValidation.isValid(snapshot)) { "Invalid meal status snapshot" }
                    require(snapshot.id == p.requestId && snapshot.status == p.status &&
                        snapshot.mainResponse == p.mainResponse) { "Meal status snapshot mismatch" }
                    snapshot.toFollowerEntity(event.canonicalSequence)
                } else {
                    // Compatibility with decisions published before snapshots were added.
                    checkNotNull(existing) { "Meal status event requires its request before cursor advance" }
                    existing.copy(status = p.status, mainResponse = p.mainResponse,
                        updatedAt = event.occurredAt, canonicalSequence = event.canonicalSequence)
                }
            }
            else -> return
        }
        val existing = dao.getById(candidate.id)
        if (existing != null) {
            require(existing.mealId == candidate.mealId &&
                existing.authorDeviceId == candidate.authorDeviceId &&
                existing.requestText == candidate.requestText) { "Meal request identity mismatch" }
            if (existing.canonicalSequence >= candidate.canonicalSequence) return
        }
        dao.upsert(candidate)
    }
}
