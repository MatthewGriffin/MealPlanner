package com.example.chatgpt_mealplanner.core.backup

import kotlinx.serialization.Serializable

@Serializable
data class CanonicalSyncOperationExportDto(
    val operationId: String,
    val deviceId: String,
    val deviceSequence: Long,
    val protocolVersion: Int,
    val operationType: String,
    val targetItemId: String?,
    val baseRevision: Long?,
    val payloadJson: String,
    val createdAt: Long,
    val canonicalSequence: Long,
    val receivedAt: Long,
    val status: String,
    val resultCode: String,
    val resultPayloadJson: String?
)

@Serializable
data class MainHouseholdBackupDto(
    val backupFormatVersion: String = "pantry-household-backup-v1",
    val exportedAt: Long,
    val appVersionName: String = "1.0",
    val roomSchemaVersion: Int = 16,
    val role: String = "MAIN",
    val householdId: String? = null,
    val householdName: String? = null,
    val mainDeviceId: String? = null,
    val nextCanonicalSequence: Long? = null,
    val inventory: List<InventoryItemExportDto> = emptyList(),
    val transactions: List<TransactionExportDto> = emptyList(),
    val meals: List<MealExportDto> = emptyList(),
    val leftovers: List<LeftoverExportDto> = emptyList(),
    val shoppingLists: List<ShoppingListExportDto> = emptyList(),
    val stapleRules: List<StapleRuleExportDto> = emptyList(),
    val productBarcodeMappings: List<BarcodeMappingExportDto> = emptyList(),
    val chatConversations: List<ChatConversationExportDto> = emptyList(),
    val canonicalOperations: List<CanonicalSyncOperationExportDto> = emptyList()
)
