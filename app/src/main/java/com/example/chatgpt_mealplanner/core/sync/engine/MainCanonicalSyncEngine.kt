package com.example.chatgpt_mealplanner.core.sync.engine

import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.notification.AppNotificationManager
import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.MealChangeRequestDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity
import com.example.chatgpt_mealplanner.sync.contract.CreateMealChangeRequestPayloadDto
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.sync.contract.AddItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.AdjustQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.EditItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.RequestDiscardPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SetQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationResultDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class MainCanonicalSyncEngine @Inject constructor(
    private val canonicalSyncDao: CanonicalSyncDao,
    private val pairedDeviceDao: PairedDeviceDao,
    private val inventoryDao: InventoryDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val mealChangeRequestDao: MealChangeRequestDao? = null,
    private val mealDao: MealDao? = null,
    private val notificationManager: AppNotificationManager? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun processOperation(
        sessionDeviceId: String,
        dto: SyncOperationDto
    ): SyncOperationResultDto {
        val result = transactionRunner.runInTransaction {
            val now = System.currentTimeMillis()

            // 1. Auth Binding & Device Status Check
            if (sessionDeviceId != dto.deviceId) {
                return@runInTransaction SyncOperationResultDto(
                    operationId = dto.operationId,
                    deviceId = dto.deviceId,
                    status = SyncOperationStatus.REJECTED,
                    resultCode = "AUTHENTICATED_DEVICE_MISMATCH",
                    resultMessage = "Operation deviceId does not match authenticated session deviceId."
                )
            }

            val pairedDevice = pairedDeviceDao.getDeviceById(dto.deviceId)
            if (pairedDevice == null || pairedDevice.status != "ACTIVE") {
                return@runInTransaction SyncOperationResultDto(
                    operationId = dto.operationId,
                    deviceId = dto.deviceId,
                    status = SyncOperationStatus.REJECTED,
                    resultCode = "DEVICE_REVOKED",
                    resultMessage = "Device is not registered or has been revoked."
                )
            }

            // 2. Protocol Validation
            if (!SyncContractValidation.validateOperation(dto)) {
                return@runInTransaction SyncOperationResultDto(
                    operationId = dto.operationId,
                    deviceId = dto.deviceId,
                    status = SyncOperationStatus.REJECTED,
                    resultCode = "MALFORMED_PROTOCOL_PAYLOAD",
                    resultMessage = "Operation payload or headers failed protocol validation."
                )
            }

            // 3. Idempotency Check (operationId)
            val existingOp = canonicalSyncDao.getByOperationId(dto.operationId)
            if (existingOp != null) {
                val contentMatches = existingOp.deviceId == dto.deviceId &&
                        existingOp.deviceSequence == dto.deviceSequence &&
                        existingOp.protocolVersion == dto.protocolVersion &&
                        existingOp.operationType == dto.operationType.name &&
                        existingOp.targetItemId == dto.targetItemId &&
                        existingOp.baseRevision == dto.baseRevision &&
                        existingOp.payloadJson == dto.payloadJson &&
                        existingOp.createdAt == dto.createdAt

                if (contentMatches) {
                    val status = runCatching { SyncOperationStatus.valueOf(existingOp.status) }.getOrDefault(SyncOperationStatus.REJECTED)
                    val conflictId = if (status == SyncOperationStatus.CONFLICT) canonicalSyncDao.getConflictByOperationId(dto.operationId)?.conflictId else null
                    val approvalId = if (status == SyncOperationStatus.PENDING_APPROVAL) canonicalSyncDao.getApprovalByOperationId(dto.operationId)?.approvalId else null

                    return@runInTransaction SyncOperationResultDto(
                        operationId = dto.operationId,
                        deviceId = dto.deviceId,
                        status = status,
                        canonicalSequence = existingOp.canonicalSequence,
                        targetItemId = existingOp.targetItemId,
                        resultCode = existingOp.resultCode,
                        resultMessage = "Durable idempotent response.",
                        conflictId = conflictId,
                        approvalId = approvalId
                    )
                } else {
                    return@runInTransaction SyncOperationResultDto(
                        operationId = dto.operationId,
                        deviceId = dto.deviceId,
                        status = SyncOperationStatus.REJECTED,
                        resultCode = "OPERATION_ID_REUSE_TAMPERED",
                        resultMessage = "OperationId reused with altered content."
                    )
                }
            }

            // 4. Device Sequence Reuse Check (deviceId, deviceSequence)
            val existingSeqOp = canonicalSyncDao.getByDeviceSequence(dto.deviceId, dto.deviceSequence)
            if (existingSeqOp != null) {
                return@runInTransaction SyncOperationResultDto(
                    operationId = dto.operationId,
                    deviceId = dto.deviceId,
                    status = SyncOperationStatus.REJECTED,
                    resultCode = "DEVICE_SEQUENCE_REUSE",
                    resultMessage = "Sequence number ${dto.deviceSequence} already used for operation ${existingSeqOp.operationId}."
                )
            }

            // 5. Sequence Allocation (Starting at 1)
            val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(singletonId = 1, nextSequence = 1L)
            val allocatedSequence = seqEntity.nextSequence
            canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

            // 6. Process Operation Semantics
            when (dto.operationType) {
                SyncOperationType.CREATE_MEAL_CHANGE_REQUEST,
                SyncOperationType.UPDATE_MEAL_CHANGE_REQUEST_STATUS -> {
                    // A follower may propose a change but must never decide its status.
                    // Main writes the request and canonical operation in one Room transaction.
                    val payload = if (dto.operationType == SyncOperationType.CREATE_MEAL_CHANGE_REQUEST) {
                        json.decodeFromString<CreateMealChangeRequestPayloadDto>(dto.payloadJson)
                    } else null
                    val existing = payload?.let { mealChangeRequestDao?.getRequestById(it.requestId) }
                    val mealExists = payload?.let { mealDao?.getMealWithIngredients(it.mealId) != null } ?: false
                    val resultCode = when {
                        payload == null -> "MAIN_ONLY_MEAL_STATUS"
                        mealChangeRequestDao == null || mealDao == null -> "MEAL_SYNC_NOT_CONFIGURED"
                        existing != null -> "MEAL_REQUEST_ID_ALREADY_EXISTS"
                        !mealExists -> "MEAL_NOT_FOUND"
                        else -> "SUCCESS"
                    }
                    val accepted = resultCode == "SUCCESS"
                    canonicalSyncDao.insertCanonicalOperation(
                        CanonicalSyncOperationEntity(
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            deviceSequence = dto.deviceSequence,
                            protocolVersion = dto.protocolVersion,
                            operationType = dto.operationType.name,
                            targetItemId = null,
                            baseRevision = null,
                            payloadJson = dto.payloadJson,
                            createdAt = dto.createdAt,
                            canonicalSequence = allocatedSequence,
                            receivedAt = now,
                            status = if (accepted) SyncOperationStatus.APPLIED.name else SyncOperationStatus.REJECTED.name,
                            resultCode = resultCode
                        )
                    )
                    if (accepted) {
                        mealChangeRequestDao!!.insertRequest(
                            MealChangeRequestEntity(
                                id = payload!!.requestId,
                                mealId = payload.mealId,
                                authorDeviceId = dto.deviceId,
                                requestText = payload.requestText,
                                status = "PENDING",
                                mainResponse = null,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }
                    return@runInTransaction SyncOperationResultDto(
                        operationId = dto.operationId,
                        deviceId = dto.deviceId,
                        status = if (accepted) SyncOperationStatus.APPLIED else SyncOperationStatus.REJECTED,
                        canonicalSequence = allocatedSequence,
                        resultCode = resultCode,
                        resultMessage = if (accepted) "Meal change request recorded for Main review." else "Meal change request was not accepted."
                    )
                }
                SyncOperationType.ADD_ITEM -> {
                    val payload = json.decodeFromString<AddItemPayloadDto>(dto.payloadJson)
                    val existingItem = inventoryDao.getItemById(payload.itemId)

                    if (existingItem != null) {
                        val resultOp = CanonicalSyncOperationEntity(
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            deviceSequence = dto.deviceSequence,
                            protocolVersion = dto.protocolVersion,
                            operationType = dto.operationType.name,
                            targetItemId = payload.itemId,
                            baseRevision = dto.baseRevision,
                            payloadJson = dto.payloadJson,
                            createdAt = dto.createdAt,
                            canonicalSequence = allocatedSequence,
                            receivedAt = now,
                            status = SyncOperationStatus.REJECTED.name,
                            resultCode = "ITEM_ID_ALREADY_EXISTS"
                        )
                        canonicalSyncDao.insertCanonicalOperation(resultOp)

                        return@runInTransaction SyncOperationResultDto(
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            status = SyncOperationStatus.REJECTED,
                            canonicalSequence = allocatedSequence,
                            targetItemId = payload.itemId,
                            resultCode = "ITEM_ID_ALREADY_EXISTS",
                            resultMessage = "Inventory item with ID ${payload.itemId} already exists."
                        )
                    }

                    val cat = runCatching { FoodCategory.valueOf(payload.category.uppercase()) }.getOrDefault(FoodCategory.CUPBOARD)
                    val loc = runCatching { StorageLocation.valueOf(payload.storageLocation.uppercase()) }.getOrDefault(StorageLocation.CUPBOARD)
                    val unit = payload.quantityUnit?.let { runCatching { QuantityUnit.valueOf(it.uppercase()) }.getOrNull() }

                    val newItem = InventoryItemEntity(
                        id = payload.itemId,
                        barcode = payload.barcode,
                        canonicalIngredientId = null,
                        canonicalName = payload.canonicalName,
                        displayName = payload.displayName,
                        brand = payload.brand,
                        category = cat,
                        storageLocation = loc,
                        quantity = payload.quantity,
                        quantityUnit = unit,
                        quantityConfidence = QuantityConfidence.EXACT,
                        status = ItemStatus.AVAILABLE,
                        frozen = false,
                        staple = false,
                        notes = payload.notes,
                        createdAt = now,
                        updatedAt = now,
                        revision = 1L
                    )
                    inventoryDao.insertItem(newItem)

                    val parentOp = CanonicalSyncOperationEntity(
                        operationId = dto.operationId,
                        deviceId = dto.deviceId,
                        deviceSequence = dto.deviceSequence,
                        protocolVersion = dto.protocolVersion,
                        operationType = dto.operationType.name,
                        targetItemId = payload.itemId,
                        baseRevision = dto.baseRevision,
                        payloadJson = dto.payloadJson,
                        createdAt = dto.createdAt,
                        canonicalSequence = allocatedSequence,
                        receivedAt = now,
                        status = SyncOperationStatus.APPLIED.name,
                        resultCode = "SUCCESS"
                    )
                    canonicalSyncDao.insertCanonicalOperation(parentOp)

                    return@runInTransaction SyncOperationResultDto(
                        operationId = dto.operationId,
                        deviceId = dto.deviceId,
                        status = SyncOperationStatus.APPLIED,
                        canonicalSequence = allocatedSequence,
                        targetItemId = payload.itemId,
                        resultingRevision = 1L,
                        resultCode = "SUCCESS"
                    )
                }

                SyncOperationType.EDIT_ITEM,
                SyncOperationType.SET_QUANTITY,
                SyncOperationType.ADJUST_QUANTITY,
                SyncOperationType.REQUEST_DISCARD_ITEM -> {
                    val targetItemId = dto.targetItemId!!
                    val targetItemEntity = inventoryDao.getItemById(targetItemId)

                    if (targetItemEntity == null) {
                        val parentOp = CanonicalSyncOperationEntity(
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            deviceSequence = dto.deviceSequence,
                            protocolVersion = dto.protocolVersion,
                            operationType = dto.operationType.name,
                            targetItemId = targetItemId,
                            baseRevision = dto.baseRevision,
                            payloadJson = dto.payloadJson,
                            createdAt = dto.createdAt,
                            canonicalSequence = allocatedSequence,
                            receivedAt = now,
                            status = SyncOperationStatus.REJECTED.name,
                            resultCode = "TARGET_NOT_FOUND"
                        )
                        canonicalSyncDao.insertCanonicalOperation(parentOp)

                        return@runInTransaction SyncOperationResultDto(
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            status = SyncOperationStatus.REJECTED,
                            canonicalSequence = allocatedSequence,
                            targetItemId = targetItemId,
                            resultCode = "TARGET_NOT_FOUND",
                            resultMessage = "Target inventory item was not found."
                        )
                    }

                    if (targetItemEntity.status == ItemStatus.DISCARDED) {
                        val parentOp = CanonicalSyncOperationEntity(
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            deviceSequence = dto.deviceSequence,
                            protocolVersion = dto.protocolVersion,
                            operationType = dto.operationType.name,
                            targetItemId = targetItemId,
                            baseRevision = dto.baseRevision,
                            payloadJson = dto.payloadJson,
                            createdAt = dto.createdAt,
                            canonicalSequence = allocatedSequence,
                            receivedAt = now,
                            status = SyncOperationStatus.REJECTED.name,
                            resultCode = "TARGET_NOT_EDITABLE"
                        )
                        canonicalSyncDao.insertCanonicalOperation(parentOp)

                        return@runInTransaction SyncOperationResultDto(
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            status = SyncOperationStatus.REJECTED,
                            canonicalSequence = allocatedSequence,
                            targetItemId = targetItemId,
                            resultCode = "TARGET_NOT_EDITABLE",
                            resultMessage = "Target inventory item is discarded and cannot be modified."
                        )
                    }

                    val targetItem = targetItemEntity.toDomainModel()

                    // Check Revision Mismatch
                    if (dto.baseRevision != targetItem.revision) {
                        val parentOp = CanonicalSyncOperationEntity(
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            deviceSequence = dto.deviceSequence,
                            protocolVersion = dto.protocolVersion,
                            operationType = dto.operationType.name,
                            targetItemId = targetItemId,
                            baseRevision = dto.baseRevision,
                            payloadJson = dto.payloadJson,
                            createdAt = dto.createdAt,
                            canonicalSequence = allocatedSequence,
                            receivedAt = now,
                            status = SyncOperationStatus.CONFLICT.name,
                            resultCode = "BASE_REVISION_MISMATCH"
                        )
                        canonicalSyncDao.insertCanonicalOperation(parentOp)

                        val conflictId = UUID.randomUUID().toString()
                        val conflictEntity = SyncConflictEntity(
                            conflictId = conflictId,
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            targetItemId = targetItemId,
                            operationType = dto.operationType.name,
                            baseRevision = dto.baseRevision!!,
                            currentRevision = targetItem.revision,
                            incomingPayloadJson = dto.payloadJson,
                            currentItemSnapshotJson = json.encodeToString(targetItem),
                            status = "OPEN",
                            createdAt = now
                        )
                        canonicalSyncDao.insertConflict(conflictEntity)

                        return@runInTransaction SyncOperationResultDto(
                            operationId = dto.operationId,
                            deviceId = dto.deviceId,
                            status = SyncOperationStatus.CONFLICT,
                            canonicalSequence = allocatedSequence,
                            targetItemId = targetItemId,
                            resultCode = "BASE_REVISION_MISMATCH",
                            resultMessage = "Operation baseRevision ${dto.baseRevision} != current revision ${targetItem.revision}.",
                            conflictId = conflictId
                        )
                    }

                    // Revision Matches -> Apply Mutation
                    when (dto.operationType) {
                        SyncOperationType.EDIT_ITEM -> {
                            val payload = json.decodeFromString<EditItemPayloadDto>(dto.payloadJson)
                            val newName = payload.displayName ?: targetItem.displayName
                            val newBrand = payload.brand ?: targetItem.brand
                            val newNotes = payload.notes ?: targetItem.notes
                            val newCat = payload.category?.let { runCatching { FoodCategory.valueOf(it.uppercase()) }.getOrNull() } ?: targetItem.category
                            val newLoc = payload.storageLocation?.let { runCatching { StorageLocation.valueOf(it.uppercase()) }.getOrNull() } ?: targetItem.storageLocation
                            val newRev = targetItem.revision + 1L

                            val updated = targetItem.copy(
                                displayName = newName,
                                brand = newBrand,
                                notes = newNotes,
                                category = newCat,
                                storageLocation = newLoc,
                                updatedAt = now,
                                revision = newRev
                            )
                            inventoryDao.updateItem(updated.toEntity())

                            val parentOp = CanonicalSyncOperationEntity(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                deviceSequence = dto.deviceSequence,
                                protocolVersion = dto.protocolVersion,
                                operationType = dto.operationType.name,
                                targetItemId = targetItemId,
                                baseRevision = dto.baseRevision,
                                payloadJson = dto.payloadJson,
                                createdAt = dto.createdAt,
                                canonicalSequence = allocatedSequence,
                                receivedAt = now,
                                status = SyncOperationStatus.APPLIED.name,
                                resultCode = "SUCCESS"
                            )
                            canonicalSyncDao.insertCanonicalOperation(parentOp)

                            return@runInTransaction SyncOperationResultDto(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                status = SyncOperationStatus.APPLIED,
                                canonicalSequence = allocatedSequence,
                                targetItemId = targetItemId,
                                resultingRevision = newRev,
                                resultCode = "SUCCESS"
                            )
                        }

                        SyncOperationType.SET_QUANTITY -> {
                            val payload = json.decodeFromString<SetQuantityPayloadDto>(dto.payloadJson)
                            val newQty = payload.absoluteQuantity

                            if (newQty.isNaN() || newQty.isInfinite() || newQty < 0.0) {
                                val parentOp = CanonicalSyncOperationEntity(
                                    operationId = dto.operationId,
                                    deviceId = dto.deviceId,
                                    deviceSequence = dto.deviceSequence,
                                    protocolVersion = dto.protocolVersion,
                                    operationType = dto.operationType.name,
                                    targetItemId = targetItemId,
                                    baseRevision = dto.baseRevision,
                                    payloadJson = dto.payloadJson,
                                    createdAt = dto.createdAt,
                                    canonicalSequence = allocatedSequence,
                                    receivedAt = now,
                                    status = SyncOperationStatus.REJECTED.name,
                                    resultCode = "INVALID_QUANTITY"
                                )
                                canonicalSyncDao.insertCanonicalOperation(parentOp)

                                return@runInTransaction SyncOperationResultDto(
                                    operationId = dto.operationId,
                                    deviceId = dto.deviceId,
                                    status = SyncOperationStatus.REJECTED,
                                    canonicalSequence = allocatedSequence,
                                    targetItemId = targetItemId,
                                    resultCode = "INVALID_QUANTITY",
                                    resultMessage = "Requested absolute quantity $newQty is negative or invalid."
                                )
                            }

                            val newStatus = if (newQty == 0.0) ItemStatus.OUT else ItemStatus.AVAILABLE
                            val newRev = targetItem.revision + 1L

                            val updated = targetItem.copy(
                                quantity = newQty,
                                status = newStatus,
                                updatedAt = now,
                                revision = newRev
                            )
                            inventoryDao.updateItem(updated.toEntity())

                            val parentOp = CanonicalSyncOperationEntity(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                deviceSequence = dto.deviceSequence,
                                protocolVersion = dto.protocolVersion,
                                operationType = dto.operationType.name,
                                targetItemId = targetItemId,
                                baseRevision = dto.baseRevision,
                                payloadJson = dto.payloadJson,
                                createdAt = dto.createdAt,
                                canonicalSequence = allocatedSequence,
                                receivedAt = now,
                                status = SyncOperationStatus.APPLIED.name,
                                resultCode = "SUCCESS"
                            )
                            canonicalSyncDao.insertCanonicalOperation(parentOp)

                            return@runInTransaction SyncOperationResultDto(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                status = SyncOperationStatus.APPLIED,
                                canonicalSequence = allocatedSequence,
                                targetItemId = targetItemId,
                                resultingRevision = newRev,
                                resultCode = "SUCCESS"
                            )
                        }

                        SyncOperationType.ADJUST_QUANTITY -> {
                            val payload = json.decodeFromString<AdjustQuantityPayloadDto>(dto.payloadJson)
                            val currentQty: Double = targetItem.quantity ?: 0.0
                            val rawResult: Double = currentQty + payload.delta

                            if (payload.delta.isNaN() || payload.delta.isInfinite() || rawResult.isNaN() || rawResult.isInfinite() || rawResult < 0.0) {
                                val parentOp = CanonicalSyncOperationEntity(
                                    operationId = dto.operationId,
                                    deviceId = dto.deviceId,
                                    deviceSequence = dto.deviceSequence,
                                    protocolVersion = dto.protocolVersion,
                                    operationType = dto.operationType.name,
                                    targetItemId = targetItemId,
                                    baseRevision = dto.baseRevision,
                                    payloadJson = dto.payloadJson,
                                    createdAt = dto.createdAt,
                                    canonicalSequence = allocatedSequence,
                                    receivedAt = now,
                                    status = SyncOperationStatus.REJECTED.name,
                                    resultCode = "INVALID_QUANTITY"
                                )
                                canonicalSyncDao.insertCanonicalOperation(parentOp)

                                return@runInTransaction SyncOperationResultDto(
                                    operationId = dto.operationId,
                                    deviceId = dto.deviceId,
                                    status = SyncOperationStatus.REJECTED,
                                    canonicalSequence = allocatedSequence,
                                    targetItemId = targetItemId,
                                    resultCode = "INVALID_QUANTITY",
                                    resultMessage = "Calculated quantity $rawResult is negative or invalid."
                                )
                            }

                            val newQty = rawResult
                            val newStatus = if (newQty == 0.0) ItemStatus.OUT else ItemStatus.AVAILABLE
                            val newRev = targetItem.revision + 1L

                            val updated = targetItem.copy(
                                quantity = newQty,
                                status = newStatus,
                                updatedAt = now,
                                revision = newRev
                            )
                            inventoryDao.updateItem(updated.toEntity())

                            val parentOp = CanonicalSyncOperationEntity(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                deviceSequence = dto.deviceSequence,
                                protocolVersion = dto.protocolVersion,
                                operationType = dto.operationType.name,
                                targetItemId = targetItemId,
                                baseRevision = dto.baseRevision,
                                payloadJson = dto.payloadJson,
                                createdAt = dto.createdAt,
                                canonicalSequence = allocatedSequence,
                                receivedAt = now,
                                status = SyncOperationStatus.APPLIED.name,
                                resultCode = "SUCCESS"
                            )
                            canonicalSyncDao.insertCanonicalOperation(parentOp)

                            return@runInTransaction SyncOperationResultDto(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                status = SyncOperationStatus.APPLIED,
                                canonicalSequence = allocatedSequence,
                                targetItemId = targetItemId,
                                resultingRevision = newRev,
                                resultCode = "SUCCESS"
                            )
                        }

                        SyncOperationType.REQUEST_DISCARD_ITEM -> {
                            // Does NOT discard inventory. Creates SyncPendingApprovalEntity.
                            val parentOp = CanonicalSyncOperationEntity(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                deviceSequence = dto.deviceSequence,
                                protocolVersion = dto.protocolVersion,
                                operationType = dto.operationType.name,
                                targetItemId = targetItemId,
                                baseRevision = dto.baseRevision,
                                payloadJson = dto.payloadJson,
                                createdAt = dto.createdAt,
                                canonicalSequence = allocatedSequence,
                                receivedAt = now,
                                status = SyncOperationStatus.PENDING_APPROVAL.name,
                                resultCode = "PENDING_MAIN_APPROVAL"
                            )
                            canonicalSyncDao.insertCanonicalOperation(parentOp)

                            val approvalId = UUID.randomUUID().toString()
                            val approvalEntity = SyncPendingApprovalEntity(
                                approvalId = approvalId,
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                targetItemId = targetItemId,
                                operationType = dto.operationType.name,
                                requestedBaseRevision = dto.baseRevision!!,
                                currentRevisionAtRequest = targetItem.revision,
                                currentItemSnapshotJson = json.encodeToString(targetItem),
                                payloadJson = dto.payloadJson,
                                status = "PENDING_APPROVAL",
                                createdAt = now
                            )
                            canonicalSyncDao.insertPendingApproval(approvalEntity)

                            return@runInTransaction SyncOperationResultDto(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                status = SyncOperationStatus.PENDING_APPROVAL,
                                canonicalSequence = allocatedSequence,
                                targetItemId = targetItemId,
                                resultingRevision = targetItem.revision, // Revision unchanged
                                resultCode = "PENDING_MAIN_APPROVAL",
                                resultMessage = "Destructive removal request submitted for Main user approval.",
                                approvalId = approvalId
                            )
                        }

                        else -> {
                            val parentOp = CanonicalSyncOperationEntity(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                deviceSequence = dto.deviceSequence,
                                protocolVersion = dto.protocolVersion,
                                operationType = dto.operationType.name,
                                targetItemId = targetItemId,
                                baseRevision = dto.baseRevision,
                                payloadJson = dto.payloadJson,
                                createdAt = dto.createdAt,
                                canonicalSequence = allocatedSequence,
                                receivedAt = now,
                                status = SyncOperationStatus.REJECTED.name,
                                resultCode = "UNSUPPORTED_OPERATION_TYPE"
                            )
                            canonicalSyncDao.insertCanonicalOperation(parentOp)

                            return@runInTransaction SyncOperationResultDto(
                                operationId = dto.operationId,
                                deviceId = dto.deviceId,
                                status = SyncOperationStatus.REJECTED,
                                canonicalSequence = allocatedSequence,
                                targetItemId = targetItemId,
                                resultCode = "UNSUPPORTED_OPERATION_TYPE"
                            )
                        }
                    }
                }
            }
        }
        if (result.status == SyncOperationStatus.APPLIED) {
            if (dto.operationType == SyncOperationType.CREATE_MEAL_CHANGE_REQUEST) {
                notificationManager?.notifyHouseholdUpdate(
                    title = "New meal change request",
                    message = "A household member has requested a change to a planned meal.",
                    notificationId = AppNotificationManager.MEAL_REQUEST_NOTIFICATION_ID
                )
            } else {
                notificationManager?.notifyHouseholdUpdate(
                    title = "Household pantry updated",
                    message = "A paired household device sent an update to shared pantry data."
                )
            }
        }
        return result
    }
}
