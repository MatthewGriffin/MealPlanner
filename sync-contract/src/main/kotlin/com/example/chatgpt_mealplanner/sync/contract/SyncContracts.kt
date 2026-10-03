package com.example.chatgpt_mealplanner.sync.contract

import kotlinx.serialization.Serializable

const val SYNC_PROTOCOL_VERSION = 1

@Serializable
enum class SyncOperationType {
    ADD_ITEM,
    EDIT_ITEM,
    SET_QUANTITY,
    ADJUST_QUANTITY,
    REQUEST_DISCARD_ITEM
}

@Serializable
data class SyncOperationDto(
    val operationId: String,
    val deviceId: String,
    val deviceSequence: Long,
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val operationType: SyncOperationType,
    val targetItemId: String? = null,
    val baseRevision: Long? = null,
    val payloadJson: String = "{}",
    val createdAt: Long
)

@Serializable
data class AuthChallengeDto(
    val challengeId: String,
    val householdId: String,
    val deviceId: String,
    val nonce: String,
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION
)

@Serializable
data class AuthChallengeResponseDto(
    val challengeId: String,
    val deviceId: String,
    val signature: String
)
