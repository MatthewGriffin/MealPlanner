package com.example.chatgpt_mealplanner.sync.contract

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncContractValidationTest {

    @Test
    fun validateOperation_validDto_returnsTrue() {
        val dto = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = UUID.randomUUID().toString(),
            deviceSequence = 1L,
            protocolVersion = SYNC_PROTOCOL_VERSION,
            operationType = SyncOperationType.ADD_ITEM,
            baseRevision = 1L,
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
