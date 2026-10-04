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
enum class SyncOperationStatus {
    APPLIED,
    CONFLICT,
    PENDING_APPROVAL,
    REJECTED
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
data class SyncOperationResultDto(
    val operationId: String,
    val deviceId: String,
    val status: SyncOperationStatus,
    val canonicalSequence: Long? = null,
    val targetItemId: String? = null,
    val resultingRevision: Long? = null,
    val resultCode: String,
    val resultMessage: String? = null,
    val conflictId: String? = null,
    val approvalId: String? = null
)

@Serializable
data class InventoryItemSyncDto(
    val itemId: String,
    val barcode: String? = null,
    val canonicalName: String,
    val displayName: String,
    val brand: String? = null,
    val category: String,
    val storageLocation: String,
    val quantity: Double? = null,
    val quantityUnit: String? = null,
    val notes: String? = null,
    val status: String,
    val revision: Long
)

@Serializable
data class SyncFeedItemDto(
    val canonicalSequence: Long,
    val operationId: String,
    val sourceDeviceId: String,
    val operationType: SyncOperationType,
    val targetItemId: String? = null,
    val baseRevision: Long? = null,
    val payloadJson: String = "{}",
    val status: SyncOperationStatus,
    val resultCode: String,
    val resultingRevision: Long? = null,
    val canonicalItemSnapshot: InventoryItemSyncDto? = null,
    val conflictId: String? = null,
    val approvalId: String? = null,
    val occurredAt: Long
)

@Serializable
data class SyncFeedResponseDto(
    val householdId: String,
    val latestCanonicalSequence: Long,
    val items: List<SyncFeedItemDto>,
    val hasMore: Boolean
)

@Serializable
data class AddItemPayloadDto(
    val itemId: String,
    val canonicalName: String,
    val displayName: String,
    val brand: String? = null,
    val category: String,
    val storageLocation: String,
    val quantity: Double? = null,
    val quantityUnit: String? = null,
    val barcode: String? = null,
    val notes: String? = null
)

@Serializable
data class EditItemPayloadDto(
    val displayName: String? = null,
    val brand: String? = null,
    val category: String? = null,
    val storageLocation: String? = null,
    val notes: String? = null
)

@Serializable
data class SetQuantityPayloadDto(
    val absoluteQuantity: Double
)

@Serializable
data class AdjustQuantityPayloadDto(
    val delta: Double
)

@Serializable
data class RequestDiscardPayloadDto(
    val reason: String? = null
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
