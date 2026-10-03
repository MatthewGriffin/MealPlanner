package com.example.chatgpt_mealplanner.sync.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncContractValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun validateOperation_validDto_returnsTrue() {
        val addPayload = AddItemPayloadDto(
            itemId = UUID.randomUUID().toString(),
            canonicalName = "Milk",
            displayName = "Whole Milk",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE"
        )
        val dto = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = UUID.randomUUID().toString(),
            deviceSequence = 1L,
            protocolVersion = SYNC_PROTOCOL_VERSION,
            operationType = SyncOperationType.ADD_ITEM,
            payloadJson = json.encodeToString(addPayload),
            createdAt = System.currentTimeMillis()
        )
        assertTrue(SyncContractValidation.validateOperation(dto))
    }

    @Test
    fun validateOperation_invalidUuidOrSequence_returnsFalse() {
        val invalidOperationId = SyncOperationDto(
            operationId = "not-a-uuid",
            deviceId = UUID.randomUUID().toString(),
            deviceSequence = 1L,
            protocolVersion = SYNC_PROTOCOL_VERSION,
            operationType = SyncOperationType.ADD_ITEM,
            createdAt = System.currentTimeMillis()
        )
        assertFalse(SyncContractValidation.validateOperation(invalidOperationId))

        val invalidSequence = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = UUID.randomUUID().toString(),
            deviceSequence = 0L,
            protocolVersion = SYNC_PROTOCOL_VERSION,
            operationType = SyncOperationType.ADD_ITEM,
            createdAt = System.currentTimeMillis()
        )
        assertFalse(SyncContractValidation.validateOperation(invalidSequence))
    }
}
