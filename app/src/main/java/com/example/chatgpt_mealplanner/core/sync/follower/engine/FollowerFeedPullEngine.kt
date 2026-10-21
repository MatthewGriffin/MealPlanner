package com.example.chatgpt_mealplanner.core.sync.follower.engine

import androidx.room.withTransaction
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.notification.AppNotificationManager
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerMealEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerBasketComparisonCachePayload
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import com.example.chatgpt_mealplanner.sync.contract.UpdateMealChangeRequestStatusPayloadDto
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao
import javax.inject.Inject
import javax.inject.Singleton

data class FollowerFeedPullSummary(
    val success: Boolean = true,
    val resultCode: String = "SUCCESS",
    val processedCount: Int = 0,
    val remoteItemsAppliedCount: Int = 0,
    val ownItemsSkippedCount: Int = 0,
    val latestCursor: Long = 0L
)

@Singleton
open class FollowerFeedPullEngine @Inject constructor(
    private val followerDatabase: FollowerDatabase?,
    private val pantrySyncTestClient: PantrySyncTestClient,
    private val localSyncEngine: FollowerLocalSyncEngine,
    private val mealRequestDao: FollowerMealRequestDao? = null,
    private val notificationManager: AppNotificationManager? = null
) {
    private val cacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    open suspend fun performFeedPullPass(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        followerDeviceId: String,
        customDao: FollowerDao? = null,
        sessionTokenProvider: (suspend (forceRefresh: Boolean) -> String)? = null
    ): FollowerFeedPullSummary {
        val feedPassStartNanos = System.nanoTime()
        val dao = customDao ?: followerDatabase?.followerDao() ?: throw IllegalStateException("FollowerDatabase or customDao required")

        var activeToken = sessionToken
        var totalProcessed = 0
        var totalApplied = 0
        var totalOwnSkipped = 0
        val requestStatusNotifications = mutableListOf<Triple<String, String, String>>()

        var hasMore = true
        var currentCursor = dao.getSyncMetadata()?.lastReconciledCanonicalSequence ?: 0L
        var pageCount = 0

        while (hasMore) {
            pageCount++
            println("[SYNC_FEED] page_start since=$currentCursor page=$pageCount")
            val httpReqStartNanos = System.nanoTime()

            var feedResult = pantrySyncTestClient.getSyncFeed(
                hostIp = hostIp,
                port = port,
                pinnedCertFingerprint = pinnedCertFingerprint,
                sessionToken = activeToken,
                sinceSequence = currentCursor,
                limit = 100
            )

            if (feedResult.status == com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.UNAUTHORIZED && sessionTokenProvider != null) {
                try {
                    activeToken = sessionTokenProvider(true)
                    feedResult = pantrySyncTestClient.getSyncFeed(
                        hostIp = hostIp,
                        port = port,
                        pinnedCertFingerprint = pinnedCertFingerprint,
                        sessionToken = activeToken,
                        sinceSequence = currentCursor,
                        limit = 100
                    )
                } catch (_: Exception) {
                    // Retain original UNAUTHORIZED status if session refresh fails
                }
            }

            val httpMs = (System.nanoTime() - httpReqStartNanos) / 1_000_000
            val httpStatus = if (feedResult.success) "200" else feedResult.status.name
            println("[SYNC_FEED] http_response status=$httpStatus duration_ms=$httpMs")

            if (!feedResult.success || feedResult.feedResponse == null) {
                val failMs = (System.nanoTime() - feedPassStartNanos) / 1_000_000
                val failureCategory = when (feedResult.status) {
                    com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.REVOKED -> "REVOKED"
                    com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.UNAUTHORIZED -> "AUTH_FAILURE"
                    com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.SERVER_ERROR -> "SERVER_FAILURE"
                    else -> "TRANSPORT_FAILURE"
                }
                println("[SYNC_FEED] failure category=$failureCategory rawError=${feedResult.error ?: ""} duration_ms=$failMs")
                return FollowerFeedPullSummary(
                    success = false,
                    resultCode = failureCategory,
                    processedCount = totalProcessed,
                    remoteItemsAppliedCount = totalApplied,
                    ownItemsSkippedCount = totalOwnSkipped,
                    latestCursor = currentCursor
                )
            }

            val response = feedResult.feedResponse
            hasMore = response.hasMore

            println("[SYNC_FEED] decoded entries=${response.items.size} latest=${response.latestCanonicalSequence} hasMore=${response.hasMore}")

            val cacheFingerprint = response.basketComparisonFingerprint
            val cachePayload = response.basketComparisonJson
            if (!cacheFingerprint.isNullOrBlank() && !cachePayload.isNullOrBlank()) {
                try {
                    followerDatabase?.mealDao()?.upsert(
                        FollowerMealEntity(
                            id = "basket_comparison_cache",
                            payloadJson = cacheJson.encodeToString(
                                FollowerBasketComparisonCachePayload(cacheFingerprint, cachePayload)
                            )
                        )
                    )
                } catch (e: Exception) {
                    println("[SYNC_FEED] basket_cache_persist_failed type=${e.javaClass.simpleName}")
                }
            }

            if (response.items.isEmpty()) {
                break
            }
            check(response.items.zipWithNext().all { (a, b) -> a.canonicalSequence < b.canonicalSequence }) {
                "Feed entries must be ordered by canonical sequence"
            }
            check(!hasMore || response.items.last().canonicalSequence > currentCursor) {
                "Feed pagination did not advance"
            }

            for (feedItem in response.items) {
                // A replayed page must never move the cursor backwards.
                if (feedItem.canonicalSequence <= currentCursor) continue
                totalProcessed++

                val block: suspend () -> Unit = {
                    val isMealEvent = feedItem.operationType == SyncOperationType.CREATE_MEAL_CHANGE_REQUEST ||
                        feedItem.operationType == SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS
                    if (isMealEvent) {
                        if (feedItem.status == SyncOperationStatus.APPLIED) {
                            val requests = checkNotNull(mealRequestDao) { "Meal projection unavailable; retaining feed cursor" }
                            if (feedItem.operationType == SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS) {
                                val update = cacheJson.decodeFromString<UpdateMealChangeRequestStatusPayloadDto>(feedItem.payloadJson)
                                val previous = requests.getById(update.requestId)
                                FollowerMealRequestProjector.apply(requests, feedItem)
                                if (previous != null && previous.authorDeviceId == followerDeviceId && previous.status != update.status) {
                                    requestStatusNotifications += Triple(update.requestId, previous.mealId, update.status)
                                }
                            } else {
                                FollowerMealRequestProjector.apply(requests, feedItem)
                            }
                        }
                    } else if (feedItem.sourceDeviceId == followerDeviceId) {
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
                    val currentMeta = dao.getSyncMetadata() ?: FollowerSyncMetadataEntity(singletonId = 1, lastReconciledCanonicalSequence = 0L)
                    dao.updateSyncMetadata(
                        currentMeta.copy(
                            lastReconciledCanonicalSequence = feedItem.canonicalSequence
                        )
                    )
                }

                if (followerDatabase != null) {
                    followerDatabase.withTransaction { block() }
                } else {
                    block()
                }
                currentCursor = feedItem.canonicalSequence
            }
        }

        val totalFeedMs = (System.nanoTime() - feedPassStartNanos) / 1_000_000
        val finalMeta = dao.getSyncMetadata() ?: FollowerSyncMetadataEntity(singletonId = 1, lastReconciledCanonicalSequence = 0L)
        dao.updateSyncMetadata(
            finalMeta.copy(
                lastSuccessfulPullAt = System.currentTimeMillis()
            )
        )
        println("[SYNC_FEED] complete pages=$pageCount duration_ms=$totalFeedMs")

        requestStatusNotifications.forEach { (requestId, mealId, status) ->
            val date = followerDatabase?.mealDao()?.getById(mealId)?.mealDate
            notificationManager?.notifyMealRequestStatus(date, requestId, status)
        }
        if (totalApplied > 0) {
            notificationManager?.notifyHouseholdUpdate(
                title = "Household pantry updated",
                message = if (totalApplied == 1) "A household member updated shared pantry data." else "$totalApplied household pantry updates were received."
            )
        }

        return FollowerFeedPullSummary(
            success = true,
            resultCode = "SUCCESS",
            processedCount = totalProcessed,
            remoteItemsAppliedCount = totalApplied,
            ownItemsSkippedCount = totalOwnSkipped,
            latestCursor = currentCursor
        )
    }
}
