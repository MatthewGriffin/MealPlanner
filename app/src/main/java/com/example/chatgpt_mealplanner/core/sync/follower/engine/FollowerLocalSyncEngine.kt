package com.example.chatgpt_mealplanner.core.sync.follower.engine

import android.content.Context
import androidx.room.withTransaction
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.sync.contract.AddItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.AdjustQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.EditItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.RequestDiscardPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SYNC_PROTOCOL_VERSION
import com.example.chatgpt_mealplanner.sync.contract.SetQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class FollowerLocalSyncEngine @Inject constructor(
    private val followerDatabase: FollowerDatabase?,
    private val followerSyncScheduler: FollowerSyncScheduler?,
    @param:ApplicationContext private val context: Context?
) {
    constructor() : this(null, null, null)

    constructor(followerDatabase: FollowerDatabase?) : this(followerDatabase, null, null)

    constructor(followerDatabase: FollowerDatabase?, followerSyncScheduler: FollowerSyncScheduler?) : this(followerDatabase, followerSyncScheduler, null)

    constructor(followerDatabase: FollowerDatabase?, context: Context?) : this(followerDatabase, null, context)
    private val json = Json { ignoreUnknownKeys = true }

    protected open suspend fun <T> runTransaction(block: suspend (FollowerDao) -> T): T {
        val db = followerDatabase ?: throw IllegalStateException("FollowerDatabase required for transactions")
        return try {
            db.withTransaction {
                block(db.followerDao())
            }
        } catch (_: Exception) {
            block(db.followerDao())
        }
    }

    private fun notifyOutboxCommitted() {
        if (context != null && followerSyncScheduler != null) {
            followerSyncScheduler.scheduleSyncWorkNonBlocking(context, "OUTBOX_COMMIT")
        }
    }

    open suspend fun rebuildPendingOutboxProjections(dao: FollowerDao) {
        val pendingOps = dao.getPendingOperationsInOrder()
        val processedItemIds = mutableSetOf<String>()

        for (op in pendingOps) {
            val itemId = op.targetItemId ?: continue
            if (processedItemIds.contains(itemId)) continue
            processedItemIds.add(itemId)

            val existing = dao.getItemById(itemId)
            if (existing == null && op.operationType == "ADD_ITEM") {
                val payload = runCatching { json.decodeFromString<com.example.chatgpt_mealplanner.sync.contract.AddItemPayloadDto>(op.payloadJson) }.getOrNull()
                if (payload != null) {
                    val newItem = FollowerInventoryItemEntity(
                        itemId = itemId,
                        barcode = payload.barcode,
                        canonicalName = payload.canonicalName,
                        confirmedDisplayName = payload.displayName,
                        confirmedBrand = payload.brand,
                        confirmedCategory = payload.category,
                        confirmedStorageLocation = payload.storageLocation,
                        confirmedQuantity = payload.quantity,
                        confirmedQuantityUnit = payload.quantityUnit,
                        confirmedNotes = payload.notes,
                        confirmedStatus = "AVAILABLE",
                        mainConfirmedRevision = op.baseRevision ?: 1L,
                        displayName = payload.displayName,
                        brand = payload.brand,
                        category = payload.category,
                        storageLocation = payload.storageLocation,
                        quantity = payload.quantity,
                        quantityUnit = payload.quantityUnit,
                        notes = payload.notes,
                        status = "AVAILABLE",
                        optimisticRevision = (op.baseRevision ?: 1L) + 1L,
                        isPendingSync = true,
                        updatedAt = op.createdAt
                    )
                    dao.insertOrUpdateItem(newItem)
                }
            } else if (existing != null) {
                recomputeItemWorkingProjection(dao, itemId)
            }
        }
    }

    open suspend fun recomputeItemWorkingProjection(dao: FollowerDao, itemId: String) {
        val item = dao.getItemById(itemId) ?: return
        val unresolvedOps = dao.getUnresolvedOutboxOpsForTargetItem(itemId)

        if (unresolvedOps.isEmpty()) {
            val revertedItem = item.copy(
                displayName = item.confirmedDisplayName,
                brand = item.confirmedBrand,
                category = item.confirmedCategory,
                storageLocation = item.confirmedStorageLocation,
                quantity = item.confirmedQuantity,
                quantityUnit = item.confirmedQuantityUnit,
                notes = item.confirmedNotes,
                status = item.confirmedStatus,
                optimisticRevision = item.mainConfirmedRevision,
                isPendingSync = false,
                updatedAt = System.currentTimeMillis()
            )
            dao.insertOrUpdateItem(revertedItem)
            return
        }

        var curName = item.confirmedDisplayName
        var curBrand = item.confirmedBrand
        var curCategory = item.confirmedCategory
        var curLocation = item.confirmedStorageLocation
        var curQuantity = item.confirmedQuantity
        val curUnit = item.confirmedQuantityUnit
        var curNotes = item.confirmedNotes
        var curStatus = item.confirmedStatus

        for (op in unresolvedOps) {
            when (SyncOperationType.valueOf(op.operationType)) {
                SyncOperationType.EDIT_ITEM -> {
                    val p = runCatching { json.decodeFromString<EditItemPayloadDto>(op.payloadJson) }.getOrNull()
                    if (p != null) {
                        curName = p.displayName ?: curName
                        curBrand = p.brand ?: curBrand
                        curCategory = p.category ?: curCategory
                        curLocation = p.storageLocation ?: curLocation
                        curNotes = p.notes ?: curNotes
                    }
                }
                SyncOperationType.SET_QUANTITY -> {
                    val p = runCatching { json.decodeFromString<SetQuantityPayloadDto>(op.payloadJson) }.getOrNull()
                    if (p != null) {
                        curQuantity = p.absoluteQuantity
                        curStatus = if (p.absoluteQuantity <= 0.0) "OUT" else "AVAILABLE"
                    }
                }
                SyncOperationType.ADJUST_QUANTITY -> {
                    val p = runCatching { json.decodeFromString<AdjustQuantityPayloadDto>(op.payloadJson) }.getOrNull()
                    if (p != null) {
                        val baseQty = curQuantity ?: 0.0
                        val newQty = (baseQty + p.delta).coerceAtLeast(0.0)
                        curQuantity = newQty
                        curStatus = if (newQty <= 0.0) "OUT" else "AVAILABLE"
                    }
                }
                SyncOperationType.REQUEST_DISCARD_ITEM -> {
                    // Does NOT discard item
                }
                SyncOperationType.ADD_ITEM -> {}
            }
        }

        val updatedItem = item.copy(
            displayName = curName,
            brand = curBrand,
            category = curCategory,
            storageLocation = curLocation,
            quantity = curQuantity,
            quantityUnit = curUnit,
            notes = curNotes,
            status = curStatus,
            isPendingSync = true,
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdateItem(updatedItem)
    }

    open suspend fun recordLocalAddItem(
        deviceId: String,
        canonicalName: String,
        displayName: String,
        category: String,
        storageLocation: String,
        quantity: Double?,
        quantityUnit: String?,
        barcode: String?,
        notes: String?,
        itemId: String = UUID.randomUUID().toString()
    ): FollowerSyncOutboxEntity {
        val result = runTransaction { dao ->
            val now = System.currentTimeMillis()

            val seqEntity = dao.getSequenceEntity() ?: FollowerDeviceSequenceEntity(singletonId = 1, nextSequence = 1L)
            val allocatedSeq = seqEntity.nextSequence
            dao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSeq + 1L))

            val operationId = UUID.randomUUID().toString()

            val payload = AddItemPayloadDto(
                itemId = itemId,
                canonicalName = canonicalName,
                displayName = displayName,
                category = category,
                storageLocation = storageLocation,
                quantity = quantity,
                quantityUnit = quantityUnit,
                barcode = barcode,
                notes = notes
            )
            val payloadJson = json.encodeToString(payload)

            val itemEntity = FollowerInventoryItemEntity(
                itemId = itemId,
                barcode = barcode,
                canonicalName = canonicalName,
                confirmedDisplayName = displayName,
                confirmedBrand = null,
                confirmedCategory = category,
                confirmedStorageLocation = storageLocation,
                confirmedQuantity = quantity,
                confirmedQuantityUnit = quantityUnit,
                confirmedNotes = notes,
                confirmedStatus = "AVAILABLE",
                mainConfirmedRevision = 1L,
                displayName = displayName,
                brand = null,
                category = category,
                storageLocation = storageLocation,
                quantity = quantity,
                quantityUnit = quantityUnit,
                notes = notes,
                status = "AVAILABLE",
                optimisticRevision = 1L,
                isPendingSync = true,
                updatedAt = now
            )
            dao.insertOrUpdateItem(itemEntity)

            val outboxEntity = FollowerSyncOutboxEntity(
                operationId = operationId,
                deviceId = deviceId,
                deviceSequence = allocatedSeq,
                protocolVersion = SYNC_PROTOCOL_VERSION,
                operationType = SyncOperationType.ADD_ITEM.name,
                targetItemId = itemId,
                baseRevision = null,
                payloadJson = payloadJson,
                createdAt = now,
                status = "PENDING",
                createdLocallyAt = now
            )
            dao.insertOutboxOperation(outboxEntity)

            outboxEntity
        }
        notifyOutboxCommitted()
        return result
    }

    open suspend fun recordLocalEditItem(
        deviceId: String,
        targetItemId: String,
        displayName: String?,
        category: String?,
        storageLocation: String?,
        notes: String?
    ): FollowerSyncOutboxEntity {
        val result = runTransaction { dao ->
            val now = System.currentTimeMillis()

            val existingItem = dao.getItemById(targetItemId)
                ?: throw IllegalArgumentException("Target item $targetItemId not found in local projection")

            val seqEntity = dao.getSequenceEntity() ?: FollowerDeviceSequenceEntity(singletonId = 1, nextSequence = 1L)
            val allocatedSeq = seqEntity.nextSequence
            dao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSeq + 1L))

            val operationId = UUID.randomUUID().toString()

            val payload = EditItemPayloadDto(
                displayName = displayName,
                category = category,
                storageLocation = storageLocation,
                notes = notes
            )
            val payloadJson = json.encodeToString(payload)

            val outboxEntity = FollowerSyncOutboxEntity(
                operationId = operationId,
                deviceId = deviceId,
                deviceSequence = allocatedSeq,
                protocolVersion = SYNC_PROTOCOL_VERSION,
                operationType = SyncOperationType.EDIT_ITEM.name,
                targetItemId = targetItemId,
                baseRevision = existingItem.mainConfirmedRevision,
                payloadJson = payloadJson,
                createdAt = now,
                status = "PENDING",
                createdLocallyAt = now
            )
            dao.insertOutboxOperation(outboxEntity)

            recomputeItemWorkingProjection(dao, targetItemId)

            outboxEntity
        }
        notifyOutboxCommitted()
        return result
    }

    open suspend fun recordLocalSetQuantity(
        deviceId: String,
        targetItemId: String,
        absoluteQuantity: Double
    ): FollowerSyncOutboxEntity {
        val result = runTransaction { dao ->
            val now = System.currentTimeMillis()

            val existingItem = dao.getItemById(targetItemId)
                ?: throw IllegalArgumentException("Target item $targetItemId not found in local projection")

            val seqEntity = dao.getSequenceEntity() ?: FollowerDeviceSequenceEntity(singletonId = 1, nextSequence = 1L)
            val allocatedSeq = seqEntity.nextSequence
            dao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSeq + 1L))

            val operationId = UUID.randomUUID().toString()

            val payload = SetQuantityPayloadDto(absoluteQuantity = absoluteQuantity)
            val payloadJson = json.encodeToString(payload)

            val outboxEntity = FollowerSyncOutboxEntity(
                operationId = operationId,
                deviceId = deviceId,
                deviceSequence = allocatedSeq,
                protocolVersion = SYNC_PROTOCOL_VERSION,
                operationType = SyncOperationType.SET_QUANTITY.name,
                targetItemId = targetItemId,
                baseRevision = existingItem.mainConfirmedRevision,
                payloadJson = payloadJson,
                createdAt = now,
                status = "PENDING",
                createdLocallyAt = now
            )
            dao.insertOutboxOperation(outboxEntity)

            recomputeItemWorkingProjection(dao, targetItemId)

            outboxEntity
        }
        notifyOutboxCommitted()
        return result
    }

    open suspend fun recordLocalAdjustQuantity(
        deviceId: String,
        targetItemId: String,
        delta: Double
    ): FollowerSyncOutboxEntity {
        val result = runTransaction { dao ->
            val now = System.currentTimeMillis()

            val existingItem = dao.getItemById(targetItemId)
                ?: throw IllegalArgumentException("Target item $targetItemId not found in local projection")

            val seqEntity = dao.getSequenceEntity() ?: FollowerDeviceSequenceEntity(singletonId = 1, nextSequence = 1L)
            val allocatedSeq = seqEntity.nextSequence
            dao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSeq + 1L))

            val operationId = UUID.randomUUID().toString()

            val payload = AdjustQuantityPayloadDto(delta = delta)
            val payloadJson = json.encodeToString(payload)

            val outboxEntity = FollowerSyncOutboxEntity(
                operationId = operationId,
                deviceId = deviceId,
                deviceSequence = allocatedSeq,
                protocolVersion = SYNC_PROTOCOL_VERSION,
                operationType = SyncOperationType.ADJUST_QUANTITY.name,
                targetItemId = targetItemId,
                baseRevision = existingItem.mainConfirmedRevision,
                payloadJson = payloadJson,
                createdAt = now,
                status = "PENDING",
                createdLocallyAt = now
            )
            dao.insertOutboxOperation(outboxEntity)

            recomputeItemWorkingProjection(dao, targetItemId)

            outboxEntity
        }
        notifyOutboxCommitted()
        return result
    }

    open suspend fun recordLocalRequestDiscard(
        deviceId: String,
        targetItemId: String,
        reason: String?
    ): FollowerSyncOutboxEntity {
        val result = runTransaction { dao ->
            val now = System.currentTimeMillis()

            val existingItem = dao.getItemById(targetItemId)
                ?: throw IllegalArgumentException("Target item $targetItemId not found in local projection")

            val seqEntity = dao.getSequenceEntity() ?: FollowerDeviceSequenceEntity(singletonId = 1, nextSequence = 1L)
            val allocatedSeq = seqEntity.nextSequence
            dao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSeq + 1L))

            val operationId = UUID.randomUUID().toString()

            val payload = RequestDiscardPayloadDto(reason = reason)
            val payloadJson = json.encodeToString(payload)

            val outboxEntity = FollowerSyncOutboxEntity(
                operationId = operationId,
                deviceId = deviceId,
                deviceSequence = allocatedSeq,
                protocolVersion = SYNC_PROTOCOL_VERSION,
                operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
                targetItemId = targetItemId,
                baseRevision = existingItem.mainConfirmedRevision,
                payloadJson = payloadJson,
                createdAt = now,
                status = "PENDING",
                createdLocallyAt = now
            )
            dao.insertOutboxOperation(outboxEntity)

            recomputeItemWorkingProjection(dao, targetItemId)

            outboxEntity
        }
        notifyOutboxCommitted()
        return result
    }
}
