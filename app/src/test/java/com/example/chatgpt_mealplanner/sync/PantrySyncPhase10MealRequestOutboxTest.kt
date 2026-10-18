package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.sync.contract.CreateMealChangeRequestPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PantrySyncPhase10MealRequestOutboxTest {
    @Test
    fun followerMealRequest_createsDurableProtocolOperationWithoutInventoryMutation() = runTest {
        val dao = FakeFollowerDao()
        val engine = TestableFollowerSyncEngine(dao)
        val deviceId = UUID.randomUUID().toString()
        val requestId = "req_${UUID.randomUUID()}"

        val operation = engine.recordLocalMealChangeRequest(
            deviceId = deviceId,
            requestId = requestId,
            mealId = "meal_123",
            requestText = "Could we change Friday dinner?"
        )

        assertEquals(1L, operation.deviceSequence)
        assertEquals("PENDING", operation.status)
        assertEquals(SyncOperationType.CREATE_MEAL_CHANGE_REQUEST.name, operation.operationType)
        assertNull(operation.targetItemId)
        assertNull(operation.baseRevision)
        assertTrue(dao.itemsMap.isEmpty())
        assertEquals(operation, dao.getOutboxByOperationId(operation.operationId))

        val payload = Json.decodeFromString<CreateMealChangeRequestPayloadDto>(operation.payloadJson)
        assertEquals(requestId, payload.requestId)
        assertEquals("meal_123", payload.mealId)
        assertTrue(SyncContractValidation.validateOperation(
            SyncOperationDto(
                operationId = operation.operationId,
                deviceId = operation.deviceId,
                deviceSequence = operation.deviceSequence,
                protocolVersion = operation.protocolVersion,
                operationType = SyncOperationType.CREATE_MEAL_CHANGE_REQUEST,
                targetItemId = operation.targetItemId,
                baseRevision = operation.baseRevision,
                payloadJson = operation.payloadJson,
                createdAt = operation.createdAt
            )
        ))
    }

    @Test
    fun mealRequestAndInventoryOperation_shareMonotonicDeviceSequence() = runTest {
        val dao = FakeFollowerDao()
        val engine = TestableFollowerSyncEngine(dao)
        val deviceId = UUID.randomUUID().toString()

        val meal = engine.recordLocalMealChangeRequest(
            deviceId, "req_${UUID.randomUUID()}", "meal_123", "Please change dinner"
        )
        val inventory = engine.recordLocalAddItem(
            deviceId = deviceId,
            canonicalName = "Milk",
            displayName = "Milk",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "LITRE",
            barcode = null,
            notes = null
        )
        assertEquals(1L, meal.deviceSequence)
        assertEquals(2L, inventory.deviceSequence)
        assertEquals(2, dao.getPendingOperationsInOrder().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedMealRequest_isRejectedBeforeOutboxWrite() = runTest {
        TestableFollowerSyncEngine(FakeFollowerDao()).recordLocalMealChangeRequest(
            UUID.randomUUID().toString(), "not-a-request-id", "meal_123", "Change dinner"
        )
    }
}
