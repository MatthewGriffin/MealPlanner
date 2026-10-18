package com.example.chatgpt_mealplanner.core.sync.follower.client

import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
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
    private val hasPendingWorkSignal = AtomicBoolean(false)

    open suspend fun performSyncUpload(
        hostIp: String,
        port: Int = 8443,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        deviceId: String,
        customDao: FollowerDao? = null,
        sessionTokenProvider: (suspend (forceRefresh: Boolean) -> String)? = null
    ): FollowerSyncUploadPassSummary {
        if (!uploadMutex.tryLock()) {
            println("[SYNC_UPLOAD] Single-flight pass active. Signaling re-check to prevent stranded operations.")
            hasPendingWorkSignal.set(true)
            return FollowerSyncUploadPassSummary(0, 0, 0, 0, 0, 0)
        }

        try {
            val dao = customDao ?: followerDatabase?.followerDao() ?: throw IllegalStateException("FollowerDatabase or customDao required")
            var totalProcessed = 0
            var totalApplied = 0
            var totalConflict = 0
            var totalApproval = 0
            var totalRejected = 0
            var totalTransportErr = 0

            do {
                hasPendingWorkSignal.set(false)

                // Recovery on startup/pass: reset stale IN_FLIGHT ops to PENDING
                dao.resetInFlightOpsToPending()

                val pendingOps = dao.getPendingOperationsInOrder()

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

                    var activeToken = sessionToken
                    var uploadResult = pantrySyncTestClient.submitSyncOperation(
                        hostIp = hostIp,
                        port = port,
                        pinnedCertFingerprint = pinnedCertFingerprint,
                        sessionToken = activeToken,
                        operationDto = dto
                    )

                    if (uploadResult.status == com.example.chatgpt_mealplanner.core.sync.client.NetworkResultStatus.UNAUTHORIZED && sessionTokenProvider != null) {
                        try {
                            activeToken = sessionTokenProvider(true)
                            uploadResult = pantrySyncTestClient.submitSyncOperation(
                                hostIp = hostIp,
                                port = port,
                                pinnedCertFingerprint = pinnedCertFingerprint,
                                sessionToken = activeToken,
                                operationDto = dto
                            )
                        } catch (_: Exception) {
                            // Retain original result if session refresh fails
                        }
                    }

                    if (!uploadResult.success && uploadResult.resultDto == null) {
                        // Transport / Connection Failure: Stop pass immediately and leave operation retryable!
                        dao.updateOutboxStatus(
                            operationId = op.operationId,
                            status = "PENDING",
                            attemptCount = op.attemptCount + 1,
                            lastAttemptAt = now,
                            lastErrorCode = uploadResult.error ?: "TRANSPORT_FAILURE"
                        )
                        totalTransportErr++
                        break // Stop upload pass on network error (preserve order)
                    }

                    val resultDto = uploadResult.resultDto
                    totalProcessed++

                    if (resultDto != null) {
                        if (op.operationType == SyncOperationType.CREATE_MEAL_CHANGE_REQUEST.name) {
                            localSyncEngine.reconcileMealRequestUpload(op, resultDto)
                            if (resultDto.status == SyncOperationStatus.APPLIED) totalApplied++ else totalRejected++
                            continue
                        }
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
                                totalApplied++
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
                                totalConflict++
                                if (op.targetItemId != null) {
                                    localSyncEngine.recomputeItemWorkingProjection(dao, op.targetItemId)
                                }
                            }
                            SyncOperationStatus.PENDING_APPROVAL -> {
                                totalApproval++
                                if (op.targetItemId != null) {
                                    localSyncEngine.recomputeItemWorkingProjection(dao, op.targetItemId)
                                }
                            }
                            SyncOperationStatus.REJECTED -> {
                                totalRejected++
                                if (op.targetItemId != null) {
                                    localSyncEngine.recomputeItemWorkingProjection(dao, op.targetItemId)
                                }
                            }
                        }
                    }
                }
            } while (totalTransportErr == 0 && (hasPendingWorkSignal.getAndSet(false) || dao.getPendingOperationsInOrder().isNotEmpty()))

            return FollowerSyncUploadPassSummary(
                processedCount = totalProcessed,
                appliedCount = totalApplied,
                conflictCount = totalConflict,
                pendingApprovalCount = totalApproval,
                rejectedCount = totalRejected,
                transportErrorCount = totalTransportErr
            )
        } finally {
            uploadMutex.unlock()
        }
    }
}
