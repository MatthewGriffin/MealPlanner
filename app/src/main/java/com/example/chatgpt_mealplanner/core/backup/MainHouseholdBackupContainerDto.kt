package com.example.chatgpt_mealplanner.core.backup

import kotlinx.serialization.Serializable

@Serializable
data class MainBackupManifestDto(
    val backupFormatVersion: String = "pantry-household-backup-v1",
    val role: String = "MAIN",
    val roomSchemaVersion: Int = 16,
    val createdAt: Long,
    val householdId: String? = null,
    val mainDeviceId: String? = null,
    val appVersionName: String = "1.0",
    val payloadSizeBytes: Long,
    val includedEntities: List<String> = listOf(
        "inventory_items",
        "inventory_transactions",
        "canonical_sync_operations",
        "canonical_sync_sequence",
        "household_identity",
        "paired_devices",
        "meals",
        "meal_ingredients",
        "leftovers",
        "shopping_lists",
        "shopping_list_items",
        "shopping_requirement_sources",
        "staple_rules",
        "product_barcode_mappings",
        "chat_conversations",
        "chat_messages"
    ),
    val excludedState: List<String> = listOf(
        "follower_sync_outbox",
        "follower_device_sequence",
        "follower_sync_metadata",
        "android_keystore_private_keys",
        "tls_private_keys"
    )
)

@Serializable
data class MainHouseholdBackupContainerDto(
    val manifest: MainBackupManifestDto,
    val payload: MainHouseholdBackupDto
)
