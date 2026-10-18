package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.sync.engine.MainCanonicalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.CreateMealChangeRequestPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.UpdateMealChangeRequestStatusPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class Phase10MainMealRequestSafetyTest {
    @Test
    fun missingMealDependencies_rejectsWithoutCreatingRequest_andReplayIsIdempotent() = runTest {
        val deviceId = UUID.randomUUID().toString()
        val paired = FakeSyncPairedDeviceDao()
        paired.insertOrUpdateDevice(PairedDeviceEntity(
            deviceId = deviceId, deviceName = "Follower",
            devicePublicKey = "test-key", status = "ACTIVE",
            pairedAt = 1L, lastSyncTimestamp = 1L
        ))
        val engine = MainCanonicalSyncEngine(
            FakeCanonicalSyncDao(), paired, FakeInventoryDao(), FakeTransactionRunner()
        )
        val operation = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.CREATE_MEAL_CHANGE_REQUEST,
            payloadJson = Json.encodeToString(CreateMealChangeRequestPayloadDto(
                "req_${UUID.randomUUID()}", "meal_1", "Please swap dinner"
            )),
            createdAt = System.currentTimeMillis()
        )
        val first = engine.processOperation(deviceId, operation)
        val replay = engine.processOperation(deviceId, operation)
        assertEquals(SyncOperationStatus.REJECTED, first.status)
        assertEquals("MEAL_SYNC_NOT_CONFIGURED", first.resultCode)
        assertEquals(first.canonicalSequence, replay.canonicalSequence)
        assertEquals(first.resultCode, replay.resultCode)
    }

    @Test
    fun followerCannotSubmitMainOnlyStatusUpdate() = runTest {
        val deviceId = UUID.randomUUID().toString()
        val paired = FakeSyncPairedDeviceDao()
        paired.insertOrUpdateDevice(PairedDeviceEntity(
            deviceId = deviceId, deviceName = "Follower",
            devicePublicKey = "test-key", status = "ACTIVE",
            pairedAt = 1L, lastSyncTimestamp = 1L
        ))
        val engine = MainCanonicalSyncEngine(
            FakeCanonicalSyncDao(), paired, FakeInventoryDao(), FakeTransactionRunner()
        )
        val operation = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS,
            payloadJson = Json.encodeToString(UpdateMealChangeRequestStatusPayloadDto(
                "req_${UUID.randomUUID()}", "ACCEPTED", null
            )),
            createdAt = System.currentTimeMillis()
        )
        val result = engine.processOperation(deviceId, operation)
        assertEquals(SyncOperationStatus.REJECTED, result.status)
        assertEquals("MAIN_ONLY_MEAL_STATUS", result.resultCode)
    }
}
