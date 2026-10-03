package com.example.chatgpt_mealplanner.sync.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncContractsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun syncOperationDto_serializationRoundTrip_preservesAllFields() {
        val dto = SyncOperationDto(
            operationId = "c4a7e912-3b8f-4f2a-9e11-823d11a45902",
            deviceId = "dev_pixel8_a1b2",
            deviceSequence = 105L,
            protocolVersion = SYNC_PROTOCOL_VERSION,
            operationType = SyncOperationType.EDIT_ITEM,
            targetItemId = "item_uuid_55",
            baseRevision = 3L,
            payloadJson = "{\"displayName\":\"Organic Whole Milk\"}",
            createdAt = 1762100000000L
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<SyncOperationDto>(encoded)

        assertEquals(dto.operationId, decoded.operationId)
        assertEquals(dto.deviceId, decoded.deviceId)
        assertEquals(dto.deviceSequence, decoded.deviceSequence)
        assertEquals(dto.protocolVersion, decoded.protocolVersion)
        assertEquals(dto.operationType, decoded.operationType)
        assertEquals(dto.targetItemId, decoded.targetItemId)
        assertEquals(dto.baseRevision, decoded.baseRevision)
        assertEquals(dto.payloadJson, decoded.payloadJson)
        assertEquals(dto.createdAt, decoded.createdAt)
    }
}
