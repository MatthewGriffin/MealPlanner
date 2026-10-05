package com.example.chatgpt_mealplanner.core.sync.repository

import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.sync.contract.AdjustQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.EditItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SetQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ResolutionResult {
    object Success : ResolutionResult
    data class NotFound(val id: String) : ResolutionResult
    data class AlreadyResolved(val id: String) : ResolutionResult
    data class Error(val message: String) : ResolutionResult
}

@Singleton
open class SyncConflictApprovalRepository @Inject constructor(
    private val canonicalSyncDao: CanonicalSyncDao,
    private val pairedDeviceDao: PairedDeviceDao,
    private val inventoryDao: InventoryDao,
    private val householdIdentityDao: HouseholdIdentityDao?,
    private val transactionRunner: DatabaseTransactionRunner
) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun getMainDeviceId(): String {
        return householdIdentityDao?.getHouseholdIdentity()?.mainDeviceId ?: "MAIN_SERVER_DEVICE"
    }

    open suspend fun getOpenConflicts(): List<SyncConflictEntity> {
        return canonicalSyncDao.getOpenConflicts()
    }

    open suspend fun getOpenPendingApprovals(): List<SyncPendingApprovalEntity> {
        return canonicalSyncDao.getOpenPendingApprovals()
    }

    open suspend fun resolveConflictKeepMain(conflictId: String): ResolutionResult {
        return transactionRunner.runInTransaction {
            val conflict = canonicalSyncDao.getConflictById(conflictId)
                ?: return@runInTransaction ResolutionResult.NotFound(conflictId)

            if (conflict.status == "RESOLVED") {
                return@runInTransaction ResolutionResult.AlreadyResolved(conflictId)
            }

            val now = System.currentTimeMillis()
            val mainDeviceId = getMainDeviceId()
            canonicalSyncDao.updateConflictStatus(conflictId, "RESOLVED", now)

            val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(1, 1L)
            val allocatedSequence = seqEntity.nextSequence
            canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

            val payloadMeta = buildJsonObject {
                put("originalOperationId", conflict.operationId)
                put("incomingPayloadJson", conflict.incomingPayloadJson)
            }.toString()

            val resOp = CanonicalSyncOperationEntity(
                operationId = "res_conflict_$conflictId",
                deviceId = mainDeviceId,
                deviceSequence = allocatedSequence,
                protocolVersion = 1,
                operationType = conflict.operationType,
                targetItemId = conflict.targetItemId,
                baseRevision = conflict.baseRevision,
                payloadJson = payloadMeta,
                createdAt = now,
                canonicalSequence = allocatedSequence,
                receivedAt = now,
                status = SyncOperationStatus.APPLIED.name,
                resultCode = "RESOLVED_KEEP_MAIN"
            )
            canonicalSyncDao.insertCanonicalOperation(resOp)

            ResolutionResult.Success
        }
    }

    open suspend fun resolveConflictApplyFollower(conflictId: String): ResolutionResult {
        return transactionRunner.runInTransaction {
            val conflict = canonicalSyncDao.getConflictById(conflictId)
                ?: return@runInTransaction ResolutionResult.NotFound(conflictId)

            if (conflict.status == "RESOLVED") {
                return@runInTransaction ResolutionResult.AlreadyResolved(conflictId)
            }

            val itemEntity = inventoryDao.getItemById(conflict.targetItemId)
                ?: return@runInTransaction ResolutionResult.Error("Target inventory item not found")

            val item = itemEntity.toDomainModel()
            val now = System.currentTimeMillis()
            val newRev = item.revision + 1L

            val updated = when (conflict.operationType) {
                SyncOperationType.EDIT_ITEM.name -> {
                    val payload = runCatching { json.decodeFromString<EditItemPayloadDto>(conflict.incomingPayloadJson) }.getOrNull()
                        ?: return@runInTransaction ResolutionResult.Error("Malformed EDIT_ITEM payload JSON")

                    item.copy(
                        displayName = payload.displayName ?: item.displayName,
                        brand = payload.brand ?: item.brand,
                        notes = payload.notes ?: item.notes,
                        updatedAt = now,
                        revision = newRev
                    )
                }
                SyncOperationType.SET_QUANTITY.name -> {
                    val payload = runCatching { json.decodeFromString<SetQuantityPayloadDto>(conflict.incomingPayloadJson) }.getOrNull()
                        ?: return@runInTransaction ResolutionResult.Error("Malformed SET_QUANTITY payload JSON")

                    if (payload.absoluteQuantity.isNaN() || payload.absoluteQuantity.isInfinite() || payload.absoluteQuantity < 0.0) {
                        return@runInTransaction ResolutionResult.Error("Invalid absolute quantity ${payload.absoluteQuantity}")
                    }

                    item.copy(
                        quantity = payload.absoluteQuantity,
                        status = if (payload.absoluteQuantity == 0.0) ItemStatus.OUT else ItemStatus.AVAILABLE,
                        updatedAt = now,
                        revision = newRev
                    )
                }
                SyncOperationType.ADJUST_QUANTITY.name -> {
                    val payload = runCatching { json.decodeFromString<AdjustQuantityPayloadDto>(conflict.incomingPayloadJson) }.getOrNull()
                        ?: return@runInTransaction ResolutionResult.Error("Malformed ADJUST_QUANTITY payload JSON")

                    val currentQty = item.quantity ?: 0.0
                    val newQty = currentQty + payload.delta

                    if (payload.delta.isNaN() || payload.delta.isInfinite() || newQty.isNaN() || newQty.isInfinite() || newQty < 0.0) {
                        return@runInTransaction ResolutionResult.Error("Invalid quantity adjustment result $newQty")
                    }

                    item.copy(
                        quantity = newQty,
                        status = if (newQty == 0.0) ItemStatus.OUT else ItemStatus.AVAILABLE,
                        updatedAt = now,
                        revision = newRev
                    )
                }
                else -> {
                    // FAIL CLOSED: Unsupported conflict operation type -> zero mutation, zero sequence allocation, conflict stays OPEN!
                    return@runInTransaction ResolutionResult.Error("Unsupported conflict operation type: ${conflict.operationType}")
                }
            }

            inventoryDao.updateItem(updated.toEntity())
            canonicalSyncDao.updateConflictStatus(conflictId, "RESOLVED", now)

            val mainDeviceId = getMainDeviceId()
            val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(1, 1L)
            val allocatedSequence = seqEntity.nextSequence
            canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

            val payloadMeta = buildJsonObject {
                put("originalOperationId", conflict.operationId)
                put("incomingPayloadJson", conflict.incomingPayloadJson)
            }.toString()

            val resOp = CanonicalSyncOperationEntity(
                operationId = "res_conflict_$conflictId",
                deviceId = mainDeviceId,
                deviceSequence = allocatedSequence,
                protocolVersion = 1,
                operationType = conflict.operationType,
                targetItemId = conflict.targetItemId,
                baseRevision = conflict.baseRevision,
                payloadJson = payloadMeta,
                createdAt = now,
                canonicalSequence = allocatedSequence,
                receivedAt = now,
                status = SyncOperationStatus.APPLIED.name,
                resultCode = "RESOLVED_APPLY_FOLLOWER"
            )
            canonicalSyncDao.insertCanonicalOperation(resOp)

            ResolutionResult.Success
        }
    }

    open suspend fun approvePendingDiscard(approvalId: String): ResolutionResult {
        return transactionRunner.runInTransaction {
            val approval = canonicalSyncDao.getApprovalById(approvalId)
                ?: return@runInTransaction ResolutionResult.NotFound(approvalId)

            if (approval.status == "APPROVED") {
                return@runInTransaction ResolutionResult.AlreadyResolved(approvalId)
            }
            if (approval.status == "REJECTED") {
                return@runInTransaction ResolutionResult.Error("Approval was already rejected")
            }

            val itemEntity = inventoryDao.getItemById(approval.targetItemId)
                ?: return@runInTransaction ResolutionResult.Error("Target inventory item not found")

            val item = itemEntity.toDomainModel()

            // Stale Discard Check: Discard ONLY if current revision matches requestedBaseRevision!
            if (item.revision != approval.requestedBaseRevision) {
                return@runInTransaction ResolutionResult.Error(
                    "Item revision has changed (current ${item.revision} != requested ${approval.requestedBaseRevision}). Discard approval cancelled to protect newer Main state."
                )
            }

            val now = System.currentTimeMillis()
            val newRev = item.revision + 1L

            // Destructive discard mutation executed ONCE
            val updated = item.copy(
                status = ItemStatus.DISCARDED,
                updatedAt = now,
                revision = newRev
            )
            inventoryDao.updateItem(updated.toEntity())
            canonicalSyncDao.updateApprovalStatus(approvalId, "APPROVED")

            val mainDeviceId = getMainDeviceId()
            val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(1, 1L)
            val allocatedSequence = seqEntity.nextSequence
            canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

            val payloadMeta = buildJsonObject {
                put("originalOperationId", approval.operationId)
                put("incomingPayloadJson", approval.payloadJson)
            }.toString()

            val resOp = CanonicalSyncOperationEntity(
                operationId = "app_discard_$approvalId",
                deviceId = mainDeviceId,
                deviceSequence = allocatedSequence,
                protocolVersion = 1,
                operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
                targetItemId = approval.targetItemId,
                baseRevision = approval.requestedBaseRevision,
                payloadJson = payloadMeta,
                createdAt = now,
                canonicalSequence = allocatedSequence,
                receivedAt = now,
                status = SyncOperationStatus.APPLIED.name,
                resultCode = "APPROVED_DISCARD"
            )
            canonicalSyncDao.insertCanonicalOperation(resOp)

            ResolutionResult.Success
        }
    }

    open suspend fun rejectPendingDiscard(approvalId: String): ResolutionResult {
        return transactionRunner.runInTransaction {
            val approval = canonicalSyncDao.getApprovalById(approvalId)
                ?: return@runInTransaction ResolutionResult.NotFound(approvalId)

            if (approval.status == "REJECTED") {
                return@runInTransaction ResolutionResult.AlreadyResolved(approvalId)
            }
            if (approval.status == "APPROVED") {
                return@runInTransaction ResolutionResult.Error("Approval was already approved")
            }

            val now = System.currentTimeMillis()
            // Inventory item remains unchanged (status stays AVAILABLE / current)
            canonicalSyncDao.updateApprovalStatus(approvalId, "REJECTED")

            val mainDeviceId = getMainDeviceId()
            val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(1, 1L)
            val allocatedSequence = seqEntity.nextSequence
            canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

            val payloadMeta = buildJsonObject {
                put("originalOperationId", approval.operationId)
                put("incomingPayloadJson", approval.payloadJson)
            }.toString()

            val resOp = CanonicalSyncOperationEntity(
                operationId = "app_discard_$approvalId",
                deviceId = mainDeviceId,
                deviceSequence = allocatedSequence,
                protocolVersion = 1,
                operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
                targetItemId = approval.targetItemId,
                baseRevision = approval.requestedBaseRevision,
                payloadJson = payloadMeta,
                createdAt = now,
                canonicalSequence = allocatedSequence,
                receivedAt = now,
                status = SyncOperationStatus.REJECTED.name,
                resultCode = "REJECTED_DISCARD"
            )
            canonicalSyncDao.insertCanonicalOperation(resOp)

            ResolutionResult.Success
        }
    }
}
