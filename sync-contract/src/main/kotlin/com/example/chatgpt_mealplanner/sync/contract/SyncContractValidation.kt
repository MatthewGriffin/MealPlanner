package com.example.chatgpt_mealplanner.sync.contract

import kotlinx.serialization.json.Json
import java.util.UUID

object SyncContractValidation {

    private val json = Json { ignoreUnknownKeys = true }

    fun isValidUuid(uuidStr: String?): Boolean {
        if (uuidStr.isNullOrBlank()) return false
        return runCatching { UUID.fromString(uuidStr) }.isSuccess
    }

    fun validateOperation(dto: SyncOperationDto): Boolean {
        if (!isValidUuid(dto.operationId)) return false
        if (!isValidUuid(dto.deviceId)) return false
        if (dto.deviceSequence < 1L) return false
        if (dto.protocolVersion != SYNC_PROTOCOL_VERSION) return false
        if (dto.createdAt <= 0L) return false

        return when (dto.operationType) {
            SyncOperationType.ADD_ITEM -> {
                val payload = runCatching { json.decodeFromString<AddItemPayloadDto>(dto.payloadJson) }.getOrNull() ?: return false
                isValidUuid(payload.itemId) && payload.canonicalName.isNotBlank() && payload.displayName.isNotBlank()
            }
            SyncOperationType.EDIT_ITEM -> {
                if (!isValidUuid(dto.targetItemId) || dto.baseRevision == null || dto.baseRevision < 1L) return false
                runCatching { json.decodeFromString<EditItemPayloadDto>(dto.payloadJson) }.isSuccess
            }
            SyncOperationType.SET_QUANTITY -> {
                if (!isValidUuid(dto.targetItemId) || dto.baseRevision == null || dto.baseRevision < 1L) return false
                val payload = runCatching { json.decodeFromString<SetQuantityPayloadDto>(dto.payloadJson) }.getOrNull() ?: return false
                !payload.absoluteQuantity.isNaN() && !payload.absoluteQuantity.isInfinite() && payload.absoluteQuantity >= 0.0
            }
            SyncOperationType.ADJUST_QUANTITY -> {
                if (!isValidUuid(dto.targetItemId) || dto.baseRevision == null || dto.baseRevision < 1L) return false
                val payload = runCatching { json.decodeFromString<AdjustQuantityPayloadDto>(dto.payloadJson) }.getOrNull() ?: return false
                !payload.delta.isNaN() && !payload.delta.isInfinite()
            }
            SyncOperationType.REQUEST_DISCARD_ITEM -> {
                if (!isValidUuid(dto.targetItemId) || dto.baseRevision == null || dto.baseRevision < 1L) return false
                runCatching { json.decodeFromString<RequestDiscardPayloadDto>(dto.payloadJson) }.isSuccess
            }
        }
    }

    fun validateAuthChallenge(dto: AuthChallengeDto): Boolean {
        if (!isValidUuid(dto.challengeId)) return false
        if (!isValidUuid(dto.householdId)) return false
        if (!isValidUuid(dto.deviceId)) return false
        if (dto.nonce.isBlank()) return false
        if (dto.protocolVersion != SYNC_PROTOCOL_VERSION) return false
        return true
    }
}
