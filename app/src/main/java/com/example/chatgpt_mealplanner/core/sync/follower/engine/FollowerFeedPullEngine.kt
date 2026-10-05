package com.example.chatgpt_mealplanner.core.sync.follower.engine

import androidx.room.withTransaction
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import javax.inject.Inject
import javax.inject.Singleton

data class FollowerFeedPullSummary(
    val processedCount: Int,
    val remoteItemsAppliedCount: Int,
    val ownItemsSkippedCount: Int,
    val latestCursor: Long
)

@Singleton
open class FollowerFeedPullEngine @Inject constructor(
    private val followerDatabase: FollowerDatabase?,
    private val pantrySyncTestClient: PantrySyncTestClient,
    private val localSyncEngine: FollowerLocalSyncEngine
) {
    open suspend fun performFeedPullPass(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        followerDeviceId: String,
        customDao: FollowerDao? = null
    ): FollowerFeedPullSummary {
        val dao = customDao ?: followerDatabase?.followerDao() ?: throw IllegalStateException("FollowerDatabase or customDao required")

        var totalProcessed = 0
        var totalApplied = 0
        var totalOwnSkipped = 0

        var hasMore = true
        var currentCursor = dao.getSyncMetadata()?.lastReconciledCanonicalSequence ?: 0L

        while (hasMore) {
            val feedResult = pantrySyncTestClient.getSyncFeed(
                hostIp = hostIp,
                port = port,
                pinnedCertFingerprint = pinnedCertFingerprint,
                sessionToken = sessionToken,
                sinceSequence = currentCursor,
                limit = 100
            )

            if (!feedResult.success || feedResult.feedResponse == null) {
                break // Stop pass on transport error
            }

            val response = feedResult.feedResponse
            hasMore = response.hasMore

            if (response.items.isEmpty()) {
                break
            }

            for (feedItem in response.items) {
                totalProcessed++

                val block: suspend () -> Unit = {
                    if (feedItem.sourceDeviceId == followerDeviceId) {
                        // Own-device operation: Already reconciled during upload, BUT still advance cursor!
                        totalOwnSkipped++
                    } else {
                        // Remote-device operation: Reconcile confirmed base state
                        val snap = feedItem.canonicalItemSnapshot
                        if (feedItem.status == SyncOperationStatus.APPLIED && snap != null) {
                            val existing = dao.getItemById(snap.itemId)

                            val updatedItem = if (existing != null) {
                                existing.copy(
                                    confirmedDisplayName = snap.displayName,
                                    confirmedBrand = snap.brand,
                                    confirmedCategory = snap.category,
                                    confirmedStorageLocation = snap.storageLocation,
                                    confirmedQuantity = snap.quantity,
                                    confirmedQuantityUnit = snap.quantityUnit,
                                    confirmedNotes = snap.notes,
                                    confirmedStatus = snap.status,
                                    mainConfirmedRevision = snap.revision
                                )
                            } else {
                                FollowerInventoryItemEntity(
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
                                    updatedAt = System.currentTimeMillis()
                                )
                            }
                            dao.insertOrUpdateItem(updatedItem)
                            localSyncEngine.recomputeItemWorkingProjection(dao, snap.itemId)
                            totalApplied++
                        }
                    }

                    val origOpId = feedItem.originalOperationId
                    if (origOpId != null) {
                        val unresolved = dao.getOutboxByOperationId(origOpId)
                        if (unresolved != null && (unresolved.status == "CONFLICT" || unresolved.status == "PENDING_APPROVAL")) {
                            val targetStatus = if (feedItem.status == SyncOperationStatus.APPLIED) "APPLIED" else "REJECTED"
                            dao.updateOutboxStatus(
                                operationId = unresolved.operationId,
                                status = targetStatus,
                                attemptCount = unresolved.attemptCount,
                                lastAttemptAt = System.currentTimeMillis(),
                                lastErrorCode = feedItem.resultCode
                            )
                        }
                    }
                    currentCursor = feedItem.canonicalSequence
                    val currentMeta = dao.getSyncMetadata() ?: FollowerSyncMetadataEntity(singletonId = 1, lastReconciledCanonicalSequence = 0L)
                    dao.updateSyncMetadata(
                        currentMeta.copy(
                            lastReconciledCanonicalSequence = currentCursor,
                            lastSuccessfulPullAt = System.currentTimeMillis()
                        )
                    )
                }

                if (followerDatabase != null) {
                    followerDatabase.withTransaction { block() }
                } else {
                    block()
                }
            }
        }

        return FollowerFeedPullSummary(
            processedCount = totalProcessed,
            remoteItemsAppliedCount = totalApplied,
            ownItemsSkippedCount = totalOwnSkipped,
            latestCursor = currentCursor
        )
    }
}
