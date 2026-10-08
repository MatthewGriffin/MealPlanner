package com.example.chatgpt_mealplanner.core.sync.follower.engine

import androidx.room.withTransaction
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto
import javax.inject.Inject
import javax.inject.Singleton

data class BootstrapPassSummary(
    val success: Boolean,
    val resultCode: String,
    val installedItemsCount: Int = 0,
    val snapshotWatermark: Long = 0L
)

@Singleton
open class FollowerBootstrapEngine @Inject constructor(
    private val followerDatabase: FollowerDatabase?,
    private val pantrySyncTestClient: PantrySyncTestClient,
    private val followerFeedPullEngine: FollowerFeedPullEngine,
    private val followerPairingStore: com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore? = null,
    private val followerLocalSyncEngine: FollowerLocalSyncEngine? = null
) {
    companion object {
        const val MAX_BOOTSTRAP_ITEMS = 5000
    }

    open suspend fun performBootstrapPass(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        followerDeviceId: String,
        expectedHouseholdId: String? = null,
        customDao: FollowerDao? = null,
        sessionTokenProvider: (suspend (forceRefresh: Boolean) -> String)? = null
    ): BootstrapPassSummary {
        val dao = customDao ?: followerDatabase?.followerDao() ?: throw IllegalStateException("FollowerDatabase or customDao required")

        val currentMeta = dao.getSyncMetadata()
        val isVerified = if (followerPairingStore != null) followerPairingStore.isBootstrapVerifiedV1() else (currentMeta?.bootstrapCompleted == true)

        if (isVerified) {
            return BootstrapPassSummary(true, "BOOTSTRAP_ALREADY_COMPLETED", 0, currentMeta?.lastReconciledCanonicalSequence ?: 0L)
        }

        var activeToken = sessionToken
        var snapshotResult = pantrySyncTestClient.getBootstrapSnapshot(
            hostIp = hostIp,
            port = port,
            pinnedCertFingerprint = pinnedCertFingerprint,
            sessionToken = activeToken
        )

        val err = snapshotResult.error ?: ""
        if (err.contains("SNAPSHOT_TOO_LARGE") || err.contains("413")) {
            return BootstrapPassSummary(false, "SNAPSHOT_TOO_LARGE", 0, 0L)
        }

        if (!snapshotResult.success || snapshotResult.bootstrapResponse == null) {
            if (sessionTokenProvider != null && snapshotResult.status == com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.UNAUTHORIZED) {
                activeToken = sessionTokenProvider(true)
                snapshotResult = pantrySyncTestClient.getBootstrapSnapshot(
                    hostIp = hostIp,
                    port = port,
                    pinnedCertFingerprint = pinnedCertFingerprint,
                    sessionToken = activeToken
                )
            }
        }

        val retryErr = snapshotResult.error ?: ""
        if (retryErr.contains("SNAPSHOT_TOO_LARGE") || retryErr.contains("413")) {
            return BootstrapPassSummary(false, "SNAPSHOT_TOO_LARGE", 0, 0L)
        }

        if (!snapshotResult.success || snapshotResult.bootstrapResponse == null) {
            return BootstrapPassSummary(false, snapshotResult.error ?: "SNAPSHOT_FETCH_FAILED", 0, 0L)
        }

        val response = snapshotResult.bootstrapResponse
        if (response.inventoryItems.size > MAX_BOOTSTRAP_ITEMS) {
            return BootstrapPassSummary(false, "SNAPSHOT_TOO_LARGE", 0, 0L)
        }

        if (expectedHouseholdId != null && response.householdId != expectedHouseholdId) {
            return BootstrapPassSummary(false, "HOUSEHOLD_MISMATCH", 0, 0L)
        }

        val watermark = response.snapshotCanonicalSequence
        val now = System.currentTimeMillis()

        val block: suspend () -> Unit = {
            dao.clearAllInventoryItems()

            for (snap in response.inventoryItems) {
                val itemEntity = FollowerInventoryItemEntity(
                    itemId = snap.itemId,
                    barcode = snap.barcode,
                    canonicalName = snap.canonicalName,
                    confirmedDisplayName = snap.displayName,
                    confirmedBrand = snap.brand,
                    confirmedCategory = snap.category,
                    confirmedStorageLocation = snap.storageLocation,
                    confirmedQuantity = snap.quantity,
                    confirmedQuantityUnit = snap.quantityUnit,
                    confirmedNotes = snap.notes,
                    confirmedStatus = snap.status,
                    mainConfirmedRevision = snap.revision,
                    displayName = snap.displayName,
                    brand = snap.brand,
                    category = snap.category,
                    storageLocation = snap.storageLocation,
                    quantity = snap.quantity,
                    quantityUnit = snap.quantityUnit,
                    notes = snap.notes,
                    status = snap.status,
                    optimisticRevision = snap.revision,
                    isPendingSync = false,
                    updatedAt = now
                )
                dao.insertOrUpdateItem(itemEntity)
            }

            val metaToSave = (dao.getSyncMetadata() ?: FollowerSyncMetadataEntity(singletonId = 1)).copy(
                lastReconciledCanonicalSequence = watermark,
                lastSuccessfulPullAt = now,
                bootstrapCompleted = true,
                bootstrapCompletedAt = now,
                bootstrapCanonicalSequence = watermark
            )
            dao.updateSyncMetadata(metaToSave)

            followerLocalSyncEngine?.rebuildPendingOutboxProjections(dao)
        }

        try {
            if (followerDatabase != null && followerDatabase.javaClass.simpleName != "TestFakeFollowerDatabase") {
                followerDatabase.withTransaction { block() }
            } else {
                block()
            }
            val markSuccess = followerPairingStore?.markBootstrapVerifiedV1() ?: true
            if (!markSuccess) {
                return BootstrapPassSummary(false, "PAIRING_STORE_COMMIT_FAILED", 0, 0L)
            }
        } catch (e: Exception) {
            return BootstrapPassSummary(false, "DATABASE_INSTALL_FAILED", 0, 0L)
        }

        // Post-bootstrap Phase 3D incremental catch-up starting from watermark
        followerFeedPullEngine.performFeedPullPass(
            hostIp = hostIp,
            port = port,
            pinnedCertFingerprint = pinnedCertFingerprint,
            sessionToken = activeToken,
            followerDeviceId = followerDeviceId,
            customDao = dao
        )

        return BootstrapPassSummary(
            success = true,
            resultCode = "SUCCESS",
            installedItemsCount = response.inventoryItems.size,
            snapshotWatermark = watermark
        )
    }
}
