package com.example.chatgpt_mealplanner.sync.contract

import kotlinx.serialization.Serializable

const val SYNC_PROTOCOL_VERSION = 1

@Serializable
enum class SyncOperationType {
    ADD_ITEM,
    EDIT_ITEM,
    SET_QUANTITY,
    ADJUST_QUANTITY,
    REQUEST_DISCARD_ITEM,
    CREATE_MEAL_CHANGE_REQUEST,
    UPDATE_MEAL_CHANGE_REQUEST_STATUS
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
    val originalOperationId: String? = null,
    val occurredAt: Long
)

@Serializable
data class SyncFeedResponseDto(
    val householdId: String,
    val latestCanonicalSequence: Long,
    val items: List<SyncFeedItemDto>,
    val hasMore: Boolean,
    val basketComparisonFingerprint: String? = null,
    val basketComparisonJson: String? = null
)

@Serializable
data class BootstrapResponseDto(
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val householdId: String,
    val snapshotCanonicalSequence: Long,
    val inventoryItems: List<InventoryItemSyncDto>,
    val mealRequests: List<MealChangeRequestSyncDto> = emptyList(),
    val mealPlan: List<MealWithIngredientsSyncDto>? = null,
    val shoppingLists: List<ShoppingListSyncDto>? = null,
    val shoppingItems: List<ShoppingItemSyncDto>? = null,
    val stapleRules: List<StapleRuleSyncDto>? = null,
    /** Which meals each shopping item is for (drives the "shop on" trip splitting). Null from older hosts. */
    val shoppingSources: List<ShoppingSourceSyncDto>? = null,
    /** The shops and prices recorded on Main (drives the best-shop card). Null from older hosts. */
    val userStores: List<UserStoreSyncDto>? = null,
    val priceObservations: List<PriceObservationSyncDto>? = null,
    val basketComparisonFingerprint: String? = null,
    val basketComparisonJson: String? = null,
    /** What the household has learned of each shop's order, read-only for followers. Null from older hosts. */
    val storeOrders: List<StoreOrderSyncDto>? = null
)

/** Where an item usually comes in a shop's walk (0 first, 1 last), averaged over [samples] trips. */
@Serializable
data class StoreOrderSyncDto(
    val storeId: String,
    val itemKey: String,
    val displayName: String,
    val avgRank: Double,
    val samples: Int
)

/** A shopping trip a follower made, sent to Main so the household learns from it. [tripId] makes a resend harmless. */
@Serializable
data class ShopTripUploadDto(
    val tripId: String,
    val storeId: String,
    val startedAt: Long,
    val endedAt: Long,
    val ticks: List<ShopTickSyncDto>
)

@Serializable
data class ShopTickSyncDto(val displayName: String, val tickedAt: Long)

@Serializable
data class ShoppingListSyncDto(
    val id: String,
    val name: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ShoppingItemSyncDto(
    val id: String,
    val shoppingListId: String,
    val canonicalIngredientId: String? = null,
    val displayName: String,
    val requiredQuantity: Double? = null,
    val requiredUnit: String? = null,
    val suggestedPurchaseQuantity: Double? = null,
    val suggestedPurchaseUnit: String? = null,
    val packCount: Int? = null,
    val reason: String,
    val priority: String,
    val status: String,
    val optional: Boolean,
    val sourceType: String,
    val hasMealRequirement: Boolean,
    val hasStapleRestock: Boolean,
    val mealShortfallQuantity: Double? = null,
    val stapleReserveTopUpQuantity: Double? = null,
    val expectedFinalInventory: Double? = null,
    val expectedFinalInventoryUnit: String? = null,
    val quantityUncertain: Boolean,
    val uncertaintyReason: String? = null,
    val requirementConfidence: String,
    val wasteRisk: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ShoppingSourceSyncDto(
    val id: String,
    val shoppingListItemId: String,
    val mealId: String,
    val mealIngredientId: String? = null,
    val mealTitle: String,
    val mealDate: String,
    val requiredQuantity: Double,
    val requiredUnit: String
)

@Serializable
data class UserStoreSyncDto(
    val id: String,
    val name: String,
    val isArchived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class PriceObservationSyncDto(
    val id: String,
    val storeId: String,
    val barcode: String? = null,
    val productName: String,
    val brand: String? = null,
    val packQuantity: Double? = null,
    val packUnit: String? = null,
    val packCount: Int? = null,
    val priceMinorUnits: Long,
    val currencyCode: String = "GBP",
    val purchasedAt: Long,
    val createdAt: Long,
    val source: String = "MANUAL",
    val canonicalName: String? = null
)

@Serializable
data class StapleRuleSyncDto(
    val id: String,
    val canonicalIngredientId: String,
    val displayName: String,
    val enabled: Boolean,
    val archivedByMerge: Boolean = false,
    val mode: String,
    val thresholdQuantity: Double? = null,
    val thresholdUnit: String? = null,
    val targetQuantity: Double? = null,
    val targetUnit: String? = null,
    val thresholdPackCount: Int? = null,
    val targetPackCount: Int? = null,
    val preferredStorageLocation: String? = null,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long
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
data class CreateMealChangeRequestPayloadDto(
    val requestId: String,
    val mealId: String,
    val requestText: String
)

@Serializable
data class UpdateMealChangeRequestStatusPayloadDto(
    val requestId: String,
    val status: String,
    val mainResponse: String? = null,
    val requestSnapshot: MealChangeRequestSyncDto? = null
)

@Serializable
data class MealChangeRequestSyncDto(
    val id: String,
    val mealId: String,
    val authorDeviceId: String,
    val requestText: String,
    val status: String,
    val mainResponse: String? = null,
    val createdAt: Long,
    val updatedAt: Long
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

@Serializable
data class QrInvitationPayloadDto(
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val householdId: String,
    val mainDeviceId: String,
    val host: String,
    val port: Int = 8443,
    val tlsCertificateFingerprint: String,
    val enrollmentToken: String,
    val expiresAt: Long,
    val purpose: String = "ENROLL_NEW_DEVICE",
    val targetDeviceId: String? = null
)

object ReauthorizationCanonicalPayload {
    fun buildPayload(
        protocolVersion: Int = SYNC_PROTOCOL_VERSION,
        purpose: String = "REAUTHORIZE_EXISTING_DEVICE",
        householdId: String,
        mainDeviceId: String,
        followerDeviceId: String,
        enrollmentToken: String
    ): ByteArray {
        val rawStr = "pantry-household-reauthorize|$protocolVersion|$purpose|$householdId|$mainDeviceId|$followerDeviceId|$enrollmentToken"
        return rawStr.toByteArray(Charsets.UTF_8)
    }
}

@Serializable
data class ReauthorizationRequestDto(
    val enrollmentToken: String,
    val householdId: String,
    val mainDeviceId: String,
    val followerDeviceId: String,
    val signature: String
)

@Serializable
data class ReauthorizationResponseDto(
    val success: Boolean,
    val householdId: String,
    val followerDeviceId: String,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class EnrollmentRequestDto(
    val enrollmentToken: String,
    val householdId: String,
    val mainDeviceId: String,
    val followerDeviceId: String,
    val followerDeviceName: String,
    val followerPublicKey: String
)

@Serializable
data class EnrollmentResponseDto(
    val success: Boolean,
    val householdId: String,
    val errorCode: String? = null,
    val errorMessage: String? = null
)
