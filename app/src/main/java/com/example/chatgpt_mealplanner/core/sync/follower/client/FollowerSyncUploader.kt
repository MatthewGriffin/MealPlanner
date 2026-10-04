package com.example.chatgpt_mealplanner.core.sync.follower.client

import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

data class FollowerSyncUploadPassSummary(
    val processedCount: Int,
    val appliedCount: Int,
    val conflictCount: Int,
    val pendingApprovalCount: Int,
    val rejectedCount: Int,
    val transportErrorCount: Int
)

@Singleton
open class FollowerSyncUploader @Inject constructor(
    private val followerDatabase: FollowerDatabase?,
    private val pantrySyncTestClient: PantrySyncTestClient,
    private val localSyncEngine: FollowerLocalSyncEngine
) {
    private val uploadMutex = Mutex()

    open suspend fun performSyncUpload(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        deviceId: String,
        customDao: FollowerDao? = null
    ): FollowerSyncUploadPassSummary {
        if (!uploadMutex.tryLock()) {
            println("[SYNC_UPLOAD] Single-flight pass already in progress. Skipping duplicate concurrent pass.")
            return FollowerSyncUploadPassSummary(0, 0, 0, 0, 0, 0)
        }

        try {
            val dao = customDao ?: followerDatabase?.followerDao() ?: throw IllegalStateException("FollowerDatabase or customDao required")

            // Recovery on startup/pass: reset stale IN_FLIGHT ops to PENDING
            dao.resetInFlightOpsToPending()

            val pendingOps = dao.getPendingOperationsInOrder()

            var processed = 0
            var applied = 0
            var conflict = 0
            var approval = 0
            var rejected = 0
            var transportErr = 0

            for (op in pendingOps) {
                val now = System.currentTimeMillis()
                dao.updateOutboxStatus(
                    operationId = op.operationId,
                    status = "IN_FLIGHT",
                    attemptCount = op.attemptCount + 1,
                    lastAttemptAt = now,
                    lastErrorCode = null
                )

                val dto = SyncOperationDto(
                    operationId = op.operationId,
                    deviceId = op.deviceId,
                    deviceSequence = op.deviceSequence,
                    protocolVersion = op.protocolVersion,
                    operationType = SyncOperationType.valueOf(op.operationType),
                    targetItemId = op.targetItemId,
                    baseRevision = op.baseRevision,
                    payloadJson = op.payloadJson,
                    createdAt = op.createdAt
                )

                val uploadResult = pantrySyncTestClient.submitSyncOperation(
                    hostIp = hostIp,
                    port = port,
                    pinnedCertFingerprint = pinnedCertFingerprint,
                    sessionToken = sessionToken,
                    operationDto = dto
                )

                if (!uploadResult.success && uploadResult.resultDto == null) {
                    // Transport / Connection Failure: Stop pass immediately and leave operation retryable!
                    dao.updateOutboxStatus(
                        operationId = op.operationId,
                        status = "PENDING",
                        attemptCount = op.attemptCount + 1,
                        lastAttemptAt = now,
                        lastErrorCode = uploadResult.error ?: "TRANSPORT_FAILURE"
                    )
                    transportErr++
                    break // Stop upload pass on network error (preserve order)
                }

                val resultDto = uploadResult.resultDto
                processed++

                if (resultDto != null) {
                    dao.reconcileOutboxResult(
                        operationId = op.operationId,
                        status = resultDto.status.name,
                        canonicalSeq = resultDto.canonicalSequence,
                        resultCode = resultDto.resultCode,
                        resultingRev = resultDto.resultingRevision,
                        conflictId = resultDto.conflictId,
                        approvalId = resultDto.approvalId,
                        completedAt = now
                    )

                    when (resultDto.status) {
                        SyncOperationStatus.APPLIED -> {
                            applied++
                            val resRev = resultDto.resultingRevision
                            if (op.targetItemId != null && resRev != null) {
                                val localItem = dao.getItemById(op.targetItemId)
                                if (localItem != null) {
                                    dao.insertOrUpdateItem(
                                        localItem.copy(
                                            confirmedDisplayName = localItem.displayName,
                                            confirmedBrand = localItem.brand,
                                            confirmedCategory = localItem.category,
                                            confirmedStorageLocation = localItem.storageLocation,
                                            confirmedQuantity = localItem.quantity,
                                            confirmedQuantityUnit = localItem.quantityUnit,
                                            confirmedNotes = localItem.notes,
                                            confirmedStatus = localItem.status,
                                            mainConfirmedRevision = resRev
                                        )
                                    )
                                    localSyncEngine.recomputeItemWorkingProjection(dao, op.targetItemId)
                                }
                            }
                        }
                        SyncOperationStatus.CONFLICT -> {
                            conflict++
                            if (op.targetItemId != null) {
                                localSyncEngine.recomputeItemWorkingProjection(dao, op.targetItemId)
                            }
                        }
                        SyncOperationStatus.PENDING_APPROVAL -> {
                            approval++
                            if (op.targetItemId != null) {
                                localSyncEngine.recomputeItemWorkingProjection(dao, op.targetItemId)
                            }
                        }
                        SyncOperationStatus.REJECTED -> {
                            rejected++
                            if (op.targetItemId != null) {
                                localSyncEngine.recomputeItemWorkingProjection(dao, op.targetItemId)
                            }
                        }
                    }
                }
            }

            return FollowerSyncUploadPassSummary(
                processedCount = processed,
                appliedCount = applied,
                conflictCount = conflict,
                pendingApprovalCount = approval,
                rejectedCount = rejected,
                transportErrorCount = transportErr
            )
        } finally {
            uploadMutex.unlock()
        }
    }
}
