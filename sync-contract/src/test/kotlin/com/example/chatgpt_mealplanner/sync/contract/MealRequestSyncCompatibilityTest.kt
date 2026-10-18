package com.example.chatgpt_mealplanner.sync.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MealRequestSyncCompatibilityTest {
    private val request = MealChangeRequestSyncDto("req_${UUID.randomUUID()}", "meal_1", "main_device",
        "Change dinner", "ACCEPTED", "Agreed", 1L, 2L)

    @Test
    fun oldBootstrapAndDecisionPayloadsStillDecode() {
        val bootstrap = Json.decodeFromString<BootstrapResponseDto>(
            """{"householdId":"home","snapshotCanonicalSequence":3,"inventoryItems":[]}""")
        assertTrue(bootstrap.mealRequests.isEmpty())
        val decision = Json.decodeFromString<UpdateMealChangeRequestStatusPayloadDto>(
            """{"requestId":"${request.id}","status":"ACCEPTED","mainResponse":"Agreed"}""")
        assertNull(decision.requestSnapshot)
    }

    @Test
    fun snapshotRoundTrips_andOldReadersCanIgnoreNewFields() {
        val payload = UpdateMealChangeRequestStatusPayloadDto(request.id, request.status, request.mainResponse, request)
        val encoded = Json.encodeToString(payload)
        assertEquals(payload, Json.decodeFromString<UpdateMealChangeRequestStatusPayloadDto>(encoded))
        assertTrue(MealRequestSyncValidation.isValid(request))
        // Existing app JSON readers use ignoreUnknownKeys; the original fields remain unchanged.
        val tree = Json.parseToJsonElement(encoded)
        assertTrue(tree.toString().contains("requestSnapshot"))
    }

    @Test
    fun malformedSnapshotsAreRejected() {
        assertFalse(MealRequestSyncValidation.isValid(request.copy(id = "invalid")))
        assertFalse(MealRequestSyncValidation.isValid(request.copy(requestText = " ")))
        assertFalse(MealRequestSyncValidation.isValid(request.copy(mainResponse = "x".repeat(2001))))
        assertFalse(MealRequestSyncValidation.isValid(request.copy(status = "APPROVED")))
    }
}
