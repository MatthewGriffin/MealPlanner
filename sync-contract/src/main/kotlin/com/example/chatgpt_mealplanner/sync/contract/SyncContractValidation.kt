package com.example.chatgpt_mealplanner.sync.contract

import java.util.UUID

object SyncContractValidation {

    fun isValidUuid(uuidStr: String?): Boolean {
        if (uuidStr.isNullOrBlank()) return false
        return runCatching { UUID.fromString(uuidStr) }.isSuccess
    }

    fun validateOperation(dto: SyncOperationDto): Boolean {
        if (!isValidUuid(dto.operationId)) return false
        if (!isValidUuid(dto.deviceId)) return false
        if (dto.deviceSequence < 1L) return false
        if (dto.protocolVersion != SYNC_PROTOCOL_VERSION) return false
        if (dto.baseRevision != null && dto.baseRevision < 1L) return false
        if (dto.createdAt <= 0L) return false
        return true
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
