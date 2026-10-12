package com.example.chatgpt_mealplanner.core.service

import android.util.Log
import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.util.BarcodeNormalizer
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.InventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemBarcodeEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryMergeLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryTransactionEntity
import com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.DeductionRequest
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StapleRuleMode
import com.example.chatgpt_mealplanner.core.model.TransactionType
import com.example.chatgpt_mealplanner.core.util.UnitConverter
import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.sync.AppRole
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.sync.follower.engine.DuplicateDiscardRequestException
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.AddItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.EditItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.RequestDiscardPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SetQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SYNC_PROTOCOL_VERSION
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryDomainServiceImpl @Inject constructor(
    private val inventoryDao: InventoryDao,
    private val inventoryBarcodeDao: InventoryBarcodeDao,
    private val transactionDao: TransactionDao,
    private val stapleRuleDao: StapleRuleDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val appRoleResolver: AppRoleResolver,
    private val canonicalSyncDao: CanonicalSyncDao,
    private val householdIdentityManager: HouseholdIdentityManager,
    private val followerDatabase: FollowerDatabase? = null,
    private val followerLocalSyncEngine: FollowerLocalSyncEngine? = null,
    private val followerIdentityStore: FollowerIdentityStore? = null
) : InventoryDomainService {

    constructor(
        inventoryDao: InventoryDao,
        inventoryBarcodeDao: InventoryBarcodeDao,
        transactionDao: TransactionDao,
        stapleRuleDao: StapleRuleDao,
        transactionRunner: DatabaseTransactionRunner
    ) : this(
        inventoryDao = inventoryDao,
        inventoryBarcodeDao = inventoryBarcodeDao,
        transactionDao = transactionDao,
        stapleRuleDao = stapleRuleDao,
        transactionRunner = transactionRunner,
        appRoleResolver = object : AppRoleResolver(object : com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(null) { override fun isPaired() = false }) {},
        canonicalSyncDao = object : CanonicalSyncDao {
            override suspend fun getByOperationId(operationId: String) = null
            override suspend fun getByDeviceSequence(deviceId: String, deviceSequence: Long) = null
            override suspend fun getFeedOperationsSince(sinceSeq: Long, limit: Int) = emptyList<CanonicalSyncOperationEntity>()
            override suspend fun getLatestCanonicalSequence() = null
            override suspend fun insertCanonicalOperation(op: CanonicalSyncOperationEntity) {}
            override suspend fun getSequenceEntity() = null
            override suspend fun updateSequenceEntity(entity: CanonicalSyncSequenceEntity) {}
            override suspend fun getOpenConflicts() = emptyList<com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity>()
            override suspend fun getConflictById(conflictId: String) = null
            override suspend fun getConflictByOperationId(operationId: String) = null
            override suspend fun insertConflict(conflict: com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity) {}
            override suspend fun updateConflictStatus(conflictId: String, status: String, resolvedAt: Long?) {}
            override suspend fun getOpenPendingApprovals() = emptyList<com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity>()
            override suspend fun getApprovalById(approvalId: String) = null
            override suspend fun getApprovalByOperationId(operationId: String) = null
            override suspend fun insertPendingApproval(approval: com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity) {}
            override suspend fun updateApprovalStatus(approvalId: String, status: String) {}
        },
        householdIdentityManager = HouseholdIdentityManager(object : com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao {
            override suspend fun getHouseholdIdentity() = null
            override fun getHouseholdIdentitySync() = null
            override suspend fun insertHouseholdIdentity(identity: com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity) {}
        })
    )

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        fun normalizeBarcode(rawBarcode: String?): String? = BarcodeNormalizer.normalize(rawBarcode)

        fun normalizeProductName(rawName: String?): String {
            if (rawName.isNullOrBlank()) return ""
            return rawName.trim().replace(Regex("\\s+"), " ").lowercase()
        }
    }

    override suspend fun addItem(item: InventoryItem): DomainResult<Unit> {
        val isMain = appRoleResolver.isMainAsync()
        return transactionRunner.runInTransaction {
            val normBarcode = normalizeBarcode(item.barcode)
            val itemToSave = if (normBarcode != item.barcode) item.copy(barcode = normBarcode) else item

            if (isMain) {
                val identity = householdIdentityManager.getOrCreateHouseholdIdentity()
                val mainDeviceId = identity.mainDeviceId
                if (mainDeviceId.isBlank()) {
                    throw IllegalStateException("Main deviceId is blank")
                }

                val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(singletonId = 1, nextSequence = 1L)
                val allocatedSequence = seqEntity.nextSequence
                canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

                val addItemPayload = AddItemPayloadDto(
                    itemId = itemToSave.id,
                    canonicalName = itemToSave.canonicalName,
                    displayName = itemToSave.displayName,
                    brand = itemToSave.brand,
                    category = itemToSave.category.name,
                    storageLocation = itemToSave.storageLocation.name,
                    quantity = itemToSave.quantity,
                    quantityUnit = itemToSave.quantityUnit?.name,
                    barcode = itemToSave.barcode,
                    notes = itemToSave.notes
                )
                val payloadJson = json.encodeToString(addItemPayload)
                val now = System.currentTimeMillis()

                val canonicalOp = CanonicalSyncOperationEntity(
                    operationId = UUID.randomUUID().toString(),
                    deviceId = mainDeviceId,
                    deviceSequence = allocatedSequence,
                    protocolVersion = SYNC_PROTOCOL_VERSION,
                    operationType = SyncOperationType.ADD_ITEM.name,
                    targetItemId = itemToSave.id,
                    baseRevision = null,
                    payloadJson = payloadJson,
                    createdAt = now,
                    canonicalSequence = allocatedSequence,
                    receivedAt = now,
                    status = SyncOperationStatus.APPLIED.name,
                    resultCode = "APPLIED",
                    resultPayloadJson = null
                )
                canonicalSyncDao.insertCanonicalOperation(canonicalOp)
            }

            inventoryDao.insertItem(itemToSave.toEntity())

            if (normBarcode != null) {
                inventoryBarcodeDao.insertBarcode(
                    InventoryItemBarcodeEntity(
                        id = UUID.randomUUID().toString(),
                        inventoryItemId = itemToSave.id,
                        rawBarcode = item.barcode ?: normBarcode,
                        normalizedBarcode = normBarcode,
                        addedAt = System.currentTimeMillis(),
                        source = "MANUAL"
                    )
                )
            }

            val transaction = InventoryTransactionEntity(
                id = UUID.randomUUID().toString(),
                inventoryItemId = itemToSave.id,
                type = TransactionType.PURCHASE,
                quantityChange = itemToSave.quantity,
                unit = itemToSave.quantityUnit,
                note = "Added initial stock",
                timestamp = System.currentTimeMillis()
            )
            transactionDao.insertTransaction(transaction)

            if (itemToSave.staple) {
                syncStapleRuleForItem(itemToSave, true)
            }

            DomainResult.Success(Unit)
        }
    }

    override suspend fun addLotFromProductInput(input: ConfirmedProductInput): DomainResult<InventoryItem> {
        if (input.displayName.isBlank()) {
            return DomainResult.Failure(DomainError.InvalidQuantity(0.0))
        }

        val normBarcode = normalizeBarcode(input.barcode)
        safeLog("[BARCODE_SCAN] rawBarcode=${input.barcode}, normalizedBarcode=$normBarcode")

        val derivedTotalQuantity = if (input.packCount != null && input.packSize != null && input.packCount > 0) {
            input.packCount * input.packSize
        } else {
            input.netQuantity
        }

        val appRole = appRoleResolver.getAppRoleAsync()
        val normProductName = normalizeProductName(input.displayName)
        val addedQuantity = derivedTotalQuantity ?: 0.0

        if (appRole == AppRole.FOLLOWER) {
            val dao = followerDatabase?.followerDao()
                ?: return DomainResult.Failure(DomainError.TransactionFailed("Follower database unavailable"))
            val followerItems = dao.getAllItems().filter { it.status != ItemStatus.DISCARDED.name }.map { it.toDomainModel() }
            val exactNameMatches = followerItems.filter {
                normalizeProductName(it.displayName) == normProductName || normalizeProductName(it.canonicalName) == normProductName
            }

            if (exactNameMatches.size == 1) {
                val targetItem = exactNameMatches.first()
                val inputUnit = input.netQuantityUnit ?: input.packSizeUnit
                if (inputUnit != null && targetItem.quantityUnit != null && inputUnit != targetItem.quantityUnit) {
                    return DomainResult.Failure(DomainError.InvalidUnitConversion(targetItem.quantityUnit!!, inputUnit))
                }

                if (addedQuantity <= 0.0 || addedQuantity.isNaN() || addedQuantity.isInfinite()) {
                    return DomainResult.Failure(DomainError.InvalidQuantity(addedQuantity))
                }

                when (val restockRes = restockItem(targetItem.id, addedQuantity = addedQuantity)) {
                    is DomainResult.Success -> {
                        val newQty = (targetItem.quantity ?: 0.0) + addedQuantity
                        val updated = targetItem.copy(quantity = newQty, status = ItemStatus.AVAILABLE)
                        return DomainResult.Success(updated)
                    }
                    is DomainResult.Failure -> return restockRes
                }
            } else if (exactNameMatches.size > 1) {
                return DomainResult.Failure(
                    DomainError.DuplicateOperation("Multiple inventory items match '${input.displayName}'. Please select which item to update or merge.")
                )
            }
        } else if (appRole == AppRole.MAIN) {
            val isBarcodeAssociated = normBarcode != null && inventoryBarcodeDao.getBarcodeEntity(normBarcode) != null
            if (!isBarcodeAssociated) {
                val allItems = inventoryDao.getAllItemsSync().filter { it.status != ItemStatus.DISCARDED }.map { it.toDomainModel() }
                val exactNameMatches = allItems.filter {
                    normalizeProductName(it.displayName) == normProductName || normalizeProductName(it.canonicalName) == normProductName
                }

                if (exactNameMatches.size == 1) {
                    val targetItem = exactNameMatches.first()
                    val inputUnit = input.netQuantityUnit ?: input.packSizeUnit
                    if (inputUnit != null && targetItem.quantityUnit != null && inputUnit != targetItem.quantityUnit) {
                        return DomainResult.Failure(DomainError.InvalidUnitConversion(targetItem.quantityUnit!!, inputUnit))
                    }

                    if (addedQuantity <= 0.0 || addedQuantity.isNaN() || addedQuantity.isInfinite()) {
                        return DomainResult.Failure(DomainError.InvalidQuantity(addedQuantity))
                    }

                    when (val restockRes = restockItem(targetItem.id, addedQuantity = addedQuantity)) {
                        is DomainResult.Success -> {
                            val now = System.currentTimeMillis()
                            if (normBarcode != null) {
                                try {
                                    inventoryBarcodeDao.insertBarcode(
                                        InventoryItemBarcodeEntity(
                                            id = UUID.randomUUID().toString(),
                                            inventoryItemId = targetItem.id,
                                            rawBarcode = input.barcode ?: normBarcode,
                                            normalizedBarcode = normBarcode,
                                            addedAt = now,
                                            source = "AUTO_ASSOCIATE_EXACT_NAME"
                                        )
                                    )
                                } catch (_: Throwable) {}
                            }
                            val updatedItem = inventoryDao.getItemById(targetItem.id)?.toDomainModel() ?: targetItem
                            return DomainResult.Success(updatedItem)
                        }
                        is DomainResult.Failure -> return restockRes
                    }
                } else if (exactNameMatches.size > 1) {
                    return DomainResult.Failure(
                        DomainError.DuplicateOperation("Multiple inventory items match '${input.displayName}'. Please select which item to update or merge.")
                    )
                }
            }
        }

        return transactionRunner.runInTransaction {
            val now = System.currentTimeMillis()

            // STEP 2 - EXACT BARCODE LOOKUP
            if (normBarcode != null) {
                val barcodeEntity = inventoryBarcodeDao.getBarcodeEntity(normBarcode)
                if (barcodeEntity != null) {
                    val existingItemEntity = inventoryDao.getItemById(barcodeEntity.inventoryItemId)
                    if (existingItemEntity != null && existingItemEntity.status != ItemStatus.DISCARDED) {
                        val existingItem = existingItemEntity.toDomainModel()
                        safeLog("[BARCODE_LOOKUP] barcode=$normBarcode, associationId=${barcodeEntity.id}, inventoryItemId=${existingItem.id}, decision=EXISTING")

                        val addedQuantity = derivedTotalQuantity ?: 0.0
                        val newQty = (existingItem.quantity ?: 0.0) + addedQuantity
                        val newPackCount = (existingItem.packCount ?: 0) + (input.packCount ?: 1)

                        val updatedItem = existingItem.copy(
                            quantity = newQty,
                            packCount = newPackCount,
                            status = ItemStatus.AVAILABLE,
                            updatedAt = now,
                            revision = existingItem.revision + 1L
                        )

                        inventoryDao.updateItem(updatedItem.toEntity())

                        val transaction = InventoryTransactionEntity(
                            id = UUID.randomUUID().toString(),
                            inventoryItemId = existingItem.id,
                            type = TransactionType.RESTOCK,
                            quantityChange = addedQuantity,
                            unit = existingItem.quantityUnit ?: input.netQuantityUnit ?: input.packSizeUnit,
                            note = "Restocked via exact barcode lookup ($normBarcode)",
                            timestamp = now
                        )
                        transactionDao.insertTransaction(transaction)

                        if (input.staple || existingItem.staple) {
                            setStapleStatus(existingItem.id, true)
                        }

                        safeLog("[INVENTORY_IDENTITY_DECISION] rawBarcode=${input.barcode}, normalizedBarcode=$normBarcode, barcodeMatchItemId=${existingItem.id}, lookupProductName=\"${input.displayName}\", decision=EXISTING_BARCODE, targetItemId=${existingItem.id}, reason=EXACT_BARCODE_ASSOCIATION_MATCH")
                        return@runInTransaction DomainResult.Success(updatedItem)
                    }
                }
            }

            // STEP 6 - NO EXACT MATCH -> CREATE NEW ITEM
            safeLog("[BARCODE_LOOKUP] barcode=$normBarcode, decision=CREATE")
            safeLog("[INVENTORY_IDENTITY_DECISION] rawBarcode=${input.barcode}, normalizedBarcode=$normBarcode, lookupProductName=\"${input.displayName}\", normalizedProductName=\"$normProductName\", exactNameMatchIds=[], decision=CREATE_NEW, reason=UNKNOWN_BARCODE_AND_UNIQUE_NAME")

            val newItem = InventoryItem(
                id = UUID.randomUUID().toString(),
                barcode = normBarcode,
                canonicalIngredientId = input.canonicalIngredientId.takeIf { !it.isNullOrBlank() },
                canonicalName = input.canonicalName ?: input.displayName,
                displayName = input.displayName,
                brand = input.brand,
                category = input.category,
                storageLocation = input.storageLocation,
                quantity = derivedTotalQuantity,
                quantityUnit = input.netQuantityUnit ?: input.packSizeUnit,
                packCount = input.packCount,
                packSize = input.packSize,
                packSizeUnit = input.packSizeUnit,
                quantityConfidence = input.quantityConfidence,
                status = ItemStatus.AVAILABLE,
                opened = input.opened,
                purchaseDate = input.purchaseDate,
                openedDate = input.openedDate,
                useByDate = input.useByDate,
                bestBeforeDate = input.bestBeforeDate,
                notes = input.notes,
                staple = input.staple,
                createdAt = now,
                updatedAt = now
            )

            inventoryDao.insertItem(newItem.toEntity())

            if (normBarcode != null) {
                inventoryBarcodeDao.insertBarcode(
                    InventoryItemBarcodeEntity(
                        id = UUID.randomUUID().toString(),
                        inventoryItemId = newItem.id,
                        rawBarcode = input.barcode ?: normBarcode,
                        normalizedBarcode = normBarcode,
                        addedAt = now,
                        source = "NEW_ITEM"
                    )
                )
            }

            val transaction = InventoryTransactionEntity(
                id = UUID.randomUUID().toString(),
                inventoryItemId = newItem.id,
                type = TransactionType.PURCHASE,
                quantityChange = newItem.quantity,
                unit = newItem.quantityUnit,
                note = if (normBarcode != null) "Scanned purchase added" else "Manual lot added",
                timestamp = now
            )
            transactionDao.insertTransaction(transaction)

            if (newItem.staple) {
                syncStapleRuleForItem(newItem, true)
            }

            DomainResult.Success(newItem)
        }
    }

    override suspend fun updateItem(item: InventoryItem): DomainResult<Unit> {
        val isMain = appRoleResolver.isMainAsync()
        return transactionRunner.runInTransaction {
            val existing = inventoryDao.getItemById(item.id)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(item.id))

            val normBarcode = normalizeBarcode(item.barcode)
            val existingModel = existing.toDomainModel()

            val normalizedItem = if (normBarcode != item.barcode) {
                item.copy(barcode = normBarcode)
            } else {
                item
            }

            val isNoOp = existingModel.copy(updatedAt = 0L, revision = 0L) ==
                    normalizedItem.copy(updatedAt = 0L, revision = 0L)

            if (isNoOp) {
                return@runInTransaction DomainResult.Success(Unit)
            }

            val nextRevision = existingModel.revision + 1L
            val now = System.currentTimeMillis()
            val updatedItemToSave = normalizedItem.copy(updatedAt = now, revision = nextRevision)

            if (isMain) {
                val identity = householdIdentityManager.getOrCreateHouseholdIdentity()
                val mainDeviceId = identity.mainDeviceId
                if (mainDeviceId.isBlank()) {
                    throw IllegalStateException("Main deviceId is blank")
                }

                val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(singletonId = 1, nextSequence = 1L)
                val allocatedSequence = seqEntity.nextSequence
                canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

                val editItemPayload = EditItemPayloadDto(
                    displayName = updatedItemToSave.displayName,
                    brand = updatedItemToSave.brand,
                    category = updatedItemToSave.category.name,
                    storageLocation = updatedItemToSave.storageLocation.name,
                    notes = updatedItemToSave.notes
                )
                val payloadJson = json.encodeToString(editItemPayload)

                val canonicalOp = CanonicalSyncOperationEntity(
                    operationId = UUID.randomUUID().toString(),
                    deviceId = mainDeviceId,
                    deviceSequence = allocatedSequence,
                    protocolVersion = SYNC_PROTOCOL_VERSION,
                    operationType = SyncOperationType.EDIT_ITEM.name,
                    targetItemId = updatedItemToSave.id,
                    baseRevision = existingModel.revision,
                    payloadJson = payloadJson,
                    createdAt = now,
                    canonicalSequence = allocatedSequence,
                    receivedAt = now,
                    status = SyncOperationStatus.APPLIED.name,
                    resultCode = "APPLIED",
                    resultPayloadJson = null
                )
                canonicalSyncDao.insertCanonicalOperation(canonicalOp)
            }

            inventoryDao.updateItem(updatedItemToSave.toEntity())

            if (normBarcode != null) {
                val existingBarcodeEntity = inventoryBarcodeDao.getBarcodeEntity(normBarcode)
                if (existingBarcodeEntity == null) {
                    inventoryBarcodeDao.insertBarcode(
                        InventoryItemBarcodeEntity(
                            id = UUID.randomUUID().toString(),
                            inventoryItemId = item.id,
                            rawBarcode = item.barcode ?: normBarcode,
                            normalizedBarcode = normBarcode,
                            addedAt = System.currentTimeMillis(),
                            source = "UPDATE_ITEM"
                        )
                    )
                }
            }

            val transaction = InventoryTransactionEntity(
                id = UUID.randomUUID().toString(),
                inventoryItemId = item.id,
                type = TransactionType.ADJUSTMENT,
                quantityChange = if (item.quantity != null && existing.quantity != null) item.quantity - existing.quantity else null,
                unit = item.quantityUnit,
                note = "Item updated",
                timestamp = System.currentTimeMillis()
            )
            transactionDao.insertTransaction(transaction)

            syncStapleRuleForItem(updatedItemToSave, updatedItemToSave.staple)

            DomainResult.Success(Unit)
        }
    }

    override suspend fun deductQuantityForMeal(request: DeductionRequest): DomainResult<Unit> {
        return transactionRunner.runInTransaction {
            if (!request.mealId.isNullOrBlank()) {
                val alreadyCooked = transactionDao.hasCookedTransactionForMeal(request.mealId)
                if (alreadyCooked) {
                    return@runInTransaction DomainResult.Failure(
                        DomainError.DuplicateOperation("Ingredients for meal ${request.mealId} have already been deducted")
                    )
                }
            }

            val itemEntity = inventoryDao.getItemById(request.itemId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(request.itemId))

            val item = itemEntity.toDomainModel()

            if (item.status == ItemStatus.OUT || item.status == ItemStatus.DISCARDED) {
                return@runInTransaction DomainResult.Failure(
                    DomainError.ItemNotUsable(request.itemId, item.status)
                )
            }

            if (request.isWholePack || item.quantityUnit == QuantityUnit.TIN || item.quantityUnit == QuantityUnit.PACK) {
                val currentPackCount = item.packCount ?: 1
                if (request.amountToDeduct > currentPackCount) {
                    return@runInTransaction DomainResult.Failure(
                        DomainError.InsufficientQuantity(
                            available = currentPackCount.toDouble(),
                            requested = request.amountToDeduct,
                            unit = item.quantityUnit
                        )
                    )
                }
                val newPackCount = currentPackCount - request.amountToDeduct.toInt()
                val newQuantity = if (item.packSize != null) item.packSize * newPackCount else item.quantity
                val newStatus = if (newPackCount <= 0) ItemStatus.OUT else item.status

                val updatedItem = item.copy(
                    packCount = newPackCount,
                    quantity = newQuantity,
                    status = newStatus,
                    updatedAt = System.currentTimeMillis(),
                    revision = item.revision + 1L
                )
                inventoryDao.updateItem(updatedItem.toEntity())

                val tx = InventoryTransactionEntity(
                    id = UUID.randomUUID().toString(),
                    inventoryItemId = item.id,
                    type = TransactionType.COOKED,
                    quantityChange = -request.amountToDeduct,
                    unit = item.quantityUnit,
                    mealId = request.mealId,
                    note = "Deducted ${request.amountToDeduct.toInt()} tin/pack(s)",
                    timestamp = System.currentTimeMillis()
                )
                transactionDao.insertTransaction(tx)
                return@runInTransaction DomainResult.Success(Unit)
            }

            val deductAmountInItemUnit = if (item.quantityUnit != null && request.unit != item.quantityUnit) {
                val convertResult = UnitConverter.convert(
                    value = request.amountToDeduct,
                    from = request.unit,
                    to = item.quantityUnit,
                    packSize = item.packSize,
                    packSizeUnit = item.packSizeUnit
                )
                when (convertResult) {
                    is DomainResult.Success -> convertResult.value
                    is DomainResult.Failure -> return@runInTransaction convertResult
                }
            } else {
                request.amountToDeduct
            }

            when (item.quantityConfidence) {
                QuantityConfidence.EXACT -> {
                    val availableQty = item.quantity ?: 0.0
                    if (deductAmountInItemUnit > availableQty) {
                        return@runInTransaction DomainResult.Failure(
                            DomainError.InsufficientQuantity(
                                available = availableQty,
                                requested = deductAmountInItemUnit,
                                unit = item.quantityUnit
                            )
                        )
                    }
                    val newQty = availableQty - deductAmountInItemUnit
                    val newStatus = if (newQty <= 0.0) ItemStatus.OUT else item.status

                    val updatedItem = item.copy(
                        quantity = newQty,
                        status = newStatus,
                        updatedAt = System.currentTimeMillis(),
                        revision = item.revision + 1L
                    )
                    inventoryDao.updateItem(updatedItem.toEntity())
                }

                QuantityConfidence.APPROXIMATE -> {
                    val availableQty = item.quantity ?: 0.0
                    val newQty = (availableQty - deductAmountInItemUnit).coerceAtLeast(0.0)
                    val newStatus = if (newQty <= 0.0) ItemStatus.OUT else item.status

                    val updatedItem = item.copy(
                        quantity = newQty,
                        status = newStatus,
                        updatedAt = System.currentTimeMillis(),
                        revision = item.revision + 1L
                    )
                    inventoryDao.updateItem(updatedItem.toEntity())
                }

                QuantityConfidence.UNKNOWN -> {
                    val updatedItem = item.copy(
                        quantity = null,
                        quantityConfidence = QuantityConfidence.UNKNOWN,
                        updatedAt = System.currentTimeMillis(),
                        revision = item.revision + 1L
                    )
                    inventoryDao.updateItem(updatedItem.toEntity())
                }
            }

            val transaction = InventoryTransactionEntity(
                id = UUID.randomUUID().toString(),
                inventoryItemId = item.id,
                type = TransactionType.COOKED,
                quantityChange = -deductAmountInItemUnit,
                unit = item.quantityUnit,
                mealId = request.mealId,
                note = "Deducted for cooked meal",
                timestamp = System.currentTimeMillis()
            )
            transactionDao.insertTransaction(transaction)

            DomainResult.Success(Unit)
        }
    }

    override suspend fun recordUserCorrection(
        itemId: String,
        newQuantity: Double?,
        newUnit: QuantityUnit?,
        newConfidence: QuantityConfidence,
        newStatus: ItemStatus?,
        note: String?
    ): DomainResult<Unit> {
        val appRole = appRoleResolver.getAppRoleAsync()
        if (appRole == AppRole.FOLLOWER) {
            if (newQuantity == null || newQuantity < 0.0 || newQuantity.isNaN() || newQuantity.isInfinite()) {
                return DomainResult.Failure(DomainError.InvalidQuantity(newQuantity ?: -1.0))
            }

            val dao = followerDatabase?.followerDao()
            val existing = if (dao != null) {
                dao.getItemById(itemId)
                    ?: return DomainResult.Failure(DomainError.ItemNotFound(itemId))
            } else null

            if (existing != null && newUnit != null) {
                val existingUnit = existing.quantityUnit?.let { runCatching { QuantityUnit.valueOf(it) }.getOrNull() }
                if (existingUnit != null && newUnit != existingUnit) {
                    return DomainResult.Failure(DomainError.InvalidUnitConversion(existingUnit, newUnit))
                }
            }

            val engine = followerLocalSyncEngine
                ?: return DomainResult.Failure(DomainError.TransactionFailed("Follower sync engine unavailable"))
            val deviceId = followerIdentityStore?.getOrCreateDeviceId()
                ?: return DomainResult.Failure(DomainError.TransactionFailed("Follower identity store unavailable"))

            try {
                engine.recordLocalSetQuantity(
                    deviceId = deviceId,
                    targetItemId = itemId,
                    absoluteQuantity = newQuantity
                )
            } catch (_: IllegalArgumentException) {
                return DomainResult.Failure(DomainError.ItemNotFound(itemId))
            } catch (e: Throwable) {
                return DomainResult.Failure(DomainError.TransactionFailed(e.message ?: "Failed to record local quantity correction"))
            }
            return DomainResult.Success(Unit)
        }

        if (appRole != AppRole.MAIN) {
            return DomainResult.Failure(DomainError.TransactionFailed("Unresolved application role"))
        }

        return transactionRunner.runInTransaction {
            val itemEntity = inventoryDao.getItemById(itemId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(itemId))

            val item = itemEntity.toDomainModel()

            val calculatedStatus = when {
                newStatus != null -> newStatus
                newQuantity != null && newQuantity <= 0.0 -> ItemStatus.OUT
                item.status == ItemStatus.OUT && newQuantity != null && newQuantity > 0.0 -> ItemStatus.AVAILABLE
                else -> item.status
            }

            val targetUnit = newUnit ?: item.quantityUnit

            val updatedItem = item.copy(
                quantity = if (newConfidence == QuantityConfidence.UNKNOWN) null else newQuantity,
                quantityUnit = if (newConfidence == QuantityConfidence.UNKNOWN) null else targetUnit,
                quantityConfidence = newConfidence,
                status = calculatedStatus,
                updatedAt = System.currentTimeMillis(),
                revision = item.revision + 1L
            )

            if (newQuantity != null) {
                val identity = householdIdentityManager.getOrCreateHouseholdIdentity()
                val mainDeviceId = identity.mainDeviceId
                if (mainDeviceId.isNotBlank()) {
                    val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(singletonId = 1, nextSequence = 1L)
                    val allocatedSequence = seqEntity.nextSequence
                    canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

                    val setQtyPayload = SetQuantityPayloadDto(absoluteQuantity = newQuantity)
                    val payloadJson = json.encodeToString(setQtyPayload)

                    val canonicalOp = CanonicalSyncOperationEntity(
                        operationId = UUID.randomUUID().toString(),
                        deviceId = mainDeviceId,
                        deviceSequence = allocatedSequence,
                        protocolVersion = SYNC_PROTOCOL_VERSION,
                        operationType = SyncOperationType.SET_QUANTITY.name,
                        targetItemId = updatedItem.id,
                        baseRevision = item.revision,
                        payloadJson = payloadJson,
                        createdAt = updatedItem.updatedAt,
                        canonicalSequence = allocatedSequence,
                        receivedAt = updatedItem.updatedAt,
                        status = SyncOperationStatus.APPLIED.name,
                        resultCode = "APPLIED",
                        resultPayloadJson = null
                    )
                    canonicalSyncDao.insertCanonicalOperation(canonicalOp)
                }
            }

            inventoryDao.updateItem(updatedItem.toEntity())

            val transaction = InventoryTransactionEntity(
                id = UUID.randomUUID().toString(),
                inventoryItemId = itemId,
                type = TransactionType.USER_CORRECTION,
                quantityChange = newQuantity,
                unit = targetUnit,
                note = note ?: "User correction applied",
                timestamp = System.currentTimeMillis()
            )
            transactionDao.insertTransaction(transaction)

            DomainResult.Success(Unit)
        }
    }

    override suspend fun markAsOut(itemId: String): DomainResult<Unit> {
        return recordUserCorrection(
            itemId = itemId,
            newQuantity = 0.0,
            newConfidence = QuantityConfidence.EXACT,
            newStatus = ItemStatus.OUT,
            note = "Marked as out of stock"
        )
    }

    override suspend fun discardItem(itemId: String, reason: String?): DomainResult<Unit> {
        val appRole = appRoleResolver.getAppRoleAsync()
        if (appRole == AppRole.FOLLOWER) {
            val dao = followerDatabase?.followerDao()
                ?: return DomainResult.Failure(DomainError.TransactionFailed("Follower database unavailable"))
            val existing = dao.getItemById(itemId)
                ?: return DomainResult.Failure(DomainError.ItemNotFound(itemId))

            if (existing.status == ItemStatus.DISCARDED.name || existing.confirmedStatus == ItemStatus.DISCARDED.name) {
                return DomainResult.Success(Unit)
            }

            val engine = followerLocalSyncEngine
                ?: return DomainResult.Failure(DomainError.TransactionFailed("Follower sync engine unavailable"))
            val deviceId = followerIdentityStore?.getOrCreateDeviceId()
                ?: return DomainResult.Failure(DomainError.TransactionFailed("Follower identity store unavailable"))

            try {
                engine.recordLocalRequestDiscard(
                    deviceId = deviceId,
                    targetItemId = itemId,
                    reason = reason
                )
            } catch (_: IllegalArgumentException) {
                return DomainResult.Failure(DomainError.ItemNotFound(itemId))
            } catch (e: DuplicateDiscardRequestException) {
                return DomainResult.Failure(DomainError.DuplicateOperation(e.message ?: "Discard request for item $itemId is already pending approval"))
            } catch (e: Throwable) {
                return DomainResult.Failure(DomainError.TransactionFailed(e.message ?: "Failed to record local discard request"))
            }
            return DomainResult.Success(Unit)
        }

        if (appRole != AppRole.MAIN) {
            return DomainResult.Failure(DomainError.TransactionFailed("Unresolved application role"))
        }

        return transactionRunner.runInTransaction {
            val itemEntity = inventoryDao.getItemById(itemId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(itemId))

            val item = itemEntity.toDomainModel()
            if (item.status == ItemStatus.DISCARDED) {
                return@runInTransaction DomainResult.Success(Unit)
            }

            val updatedItem = item.copy(
                quantity = 0.0,
                status = ItemStatus.DISCARDED,
                updatedAt = System.currentTimeMillis(),
                revision = item.revision + 1L
            )

            val identity = householdIdentityManager.getOrCreateHouseholdIdentity()
            val mainDeviceId = identity.mainDeviceId
            if (mainDeviceId.isNotBlank()) {
                val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(singletonId = 1, nextSequence = 1L)
                val allocatedSequence = seqEntity.nextSequence
                canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

                val payloadJson = json.encodeToString(RequestDiscardPayloadDto(reason = reason))

                val canonicalOp = CanonicalSyncOperationEntity(
                    operationId = UUID.randomUUID().toString(),
                    deviceId = mainDeviceId,
                    deviceSequence = allocatedSequence,
                    protocolVersion = SYNC_PROTOCOL_VERSION,
                    operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
                    targetItemId = updatedItem.id,
                    baseRevision = item.revision,
                    payloadJson = payloadJson,
                    createdAt = updatedItem.updatedAt,
                    canonicalSequence = allocatedSequence,
                    receivedAt = updatedItem.updatedAt,
                    status = SyncOperationStatus.APPLIED.name,
                    resultCode = "APPROVED_DISCARD",
                    resultPayloadJson = null
                )
                canonicalSyncDao.insertCanonicalOperation(canonicalOp)
            }

            inventoryDao.updateItem(updatedItem.toEntity())

            // Deactivate staple rule for discarded item
            syncStapleRuleForItem(updatedItem, false)

            val transaction = InventoryTransactionEntity(
                id = UUID.randomUUID().toString(),
                inventoryItemId = itemId,
                type = TransactionType.DISCARDED,
                quantityChange = 0.0,
                unit = item.quantityUnit,
                note = reason ?: "Item discarded / spoiled",
                timestamp = System.currentTimeMillis()
            )
            transactionDao.insertTransaction(transaction)

            DomainResult.Success(Unit)
        }
    }

    override suspend fun restockItem(itemId: String, addedQuantity: Double?): DomainResult<Unit> {
        if (addedQuantity == null || addedQuantity <= 0.0 || addedQuantity.isNaN() || addedQuantity.isInfinite()) {
            return DomainResult.Failure(DomainError.InvalidQuantity(addedQuantity ?: 0.0))
        }

        val appRole = appRoleResolver.getAppRoleAsync()
        if (appRole == AppRole.FOLLOWER) {
            val engine = followerLocalSyncEngine
                ?: return DomainResult.Failure(DomainError.TransactionFailed("Follower sync engine unavailable"))
            val deviceId = followerIdentityStore?.getOrCreateDeviceId()
                ?: return DomainResult.Failure(DomainError.TransactionFailed("Follower identity store unavailable"))

            try {
                engine.recordLocalRestock(
                    deviceId = deviceId,
                    targetItemId = itemId,
                    addedQuantity = addedQuantity
                )
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                return if (msg.contains("not found")) {
                    DomainResult.Failure(DomainError.ItemNotFound(itemId))
                } else {
                    DomainResult.Failure(DomainError.InvalidQuantity(addedQuantity))
                }
            } catch (e: IllegalStateException) {
                return DomainResult.Failure(DomainError.DuplicateOperation(e.message ?: "Restock blocked by pending operations"))
            } catch (e: Throwable) {
                return DomainResult.Failure(DomainError.TransactionFailed(e.message ?: "Failed to record local restock"))
            }
            return DomainResult.Success(Unit)
        }

        if (appRole != AppRole.MAIN) {
            return DomainResult.Failure(DomainError.TransactionFailed("Unresolved application role"))
        }

        return transactionRunner.runInTransaction {
            val itemEntity = inventoryDao.getItemById(itemId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(itemId))

            val item = itemEntity.toDomainModel()
            val currentQty = item.quantity ?: 0.0
            val newQty = currentQty + addedQuantity
            if (newQty.isNaN() || newQty.isInfinite() || newQty < 0.0) {
                return@runInTransaction DomainResult.Failure(DomainError.InvalidQuantity(newQty))
            }

            val updatedItem = item.copy(
                quantity = newQty,
                status = ItemStatus.AVAILABLE,
                updatedAt = System.currentTimeMillis(),
                revision = item.revision + 1L
            )

            val identity = householdIdentityManager.getOrCreateHouseholdIdentity()
            val mainDeviceId = identity.mainDeviceId
            if (mainDeviceId.isNotBlank()) {
                val seqEntity = canonicalSyncDao.getSequenceEntity() ?: CanonicalSyncSequenceEntity(singletonId = 1, nextSequence = 1L)
                val allocatedSequence = seqEntity.nextSequence
                canonicalSyncDao.updateSequenceEntity(seqEntity.copy(nextSequence = allocatedSequence + 1L))

                val setQtyPayload = SetQuantityPayloadDto(absoluteQuantity = newQty)
                val payloadJson = json.encodeToString(setQtyPayload)

                val canonicalOp = CanonicalSyncOperationEntity(
                    operationId = UUID.randomUUID().toString(),
                    deviceId = mainDeviceId,
                    deviceSequence = allocatedSequence,
                    protocolVersion = SYNC_PROTOCOL_VERSION,
                    operationType = SyncOperationType.SET_QUANTITY.name,
                    targetItemId = updatedItem.id,
                    baseRevision = item.revision,
                    payloadJson = payloadJson,
                    createdAt = updatedItem.updatedAt,
                    canonicalSequence = allocatedSequence,
                    receivedAt = updatedItem.updatedAt,
                    status = SyncOperationStatus.APPLIED.name,
                    resultCode = "APPLIED",
                    resultPayloadJson = null
                )
                canonicalSyncDao.insertCanonicalOperation(canonicalOp)
            }

            inventoryDao.updateItem(updatedItem.toEntity())

            val transaction = InventoryTransactionEntity(
                id = UUID.randomUUID().toString(),
                inventoryItemId = itemId,
                type = TransactionType.RESTOCK,
                quantityChange = addedQuantity,
                unit = item.quantityUnit,
                note = "Restocked item",
                timestamp = System.currentTimeMillis()
            )
            transactionDao.insertTransaction(transaction)

            DomainResult.Success(Unit)
        }
    }

    override suspend fun toggleStapleStatus(itemId: String): DomainResult<Boolean> {
        val itemEntity = inventoryDao.getItemById(itemId)
            ?: return DomainResult.Failure(DomainError.ItemNotFound(itemId))
        return setStapleStatus(itemId, !itemEntity.staple)
    }

    override suspend fun setStapleStatus(itemId: String, isStaple: Boolean): DomainResult<Boolean> {
        return transactionRunner.runInTransaction {
            val itemEntity = inventoryDao.getItemById(itemId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(itemId))

            val item = itemEntity.toDomainModel()

            safeLog("[STAPLE_MUTATION] itemId=$itemId, itemName=\"${item.displayName}\", oldStaple=${item.staple}, newStaple=$isStaple")

            inventoryDao.updateItem(item.copy(staple = isStaple, updatedAt = System.currentTimeMillis(), revision = item.revision + 1L).toEntity())

            syncStapleRuleForItem(item, isStaple)

            val checkItem = inventoryDao.getItemById(itemId)
            val checkRule = stapleRuleDao.getStapleRuleByCanonicalIngredientId(getCanonicalId(item))
            safeLog("[STAPLE_DB_VERIFY] itemId=$itemId, inventoryStaple=${checkItem?.staple}, stapleRuleExists=${checkRule != null}, ruleEnabled=${checkRule?.enabled}")

            DomainResult.Success(isStaple)
        }
    }

    override suspend fun mergeInventoryItems(
        survivorItemId: String,
        mergedItemId: String,
        preferredDisplayName: String?,
        preferredRuleThreshold: Double?,
        preferredRuleTarget: Double?
    ): DomainResult<InventoryItem> {
        return transactionRunner.runInTransaction {
            if (survivorItemId == mergedItemId) {
                return@runInTransaction DomainResult.Failure(DomainError.DuplicateOperation("Cannot merge item with itself."))
            }

            val survivorEntity = inventoryDao.getItemById(survivorItemId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(survivorItemId))
            val mergedEntity = inventoryDao.getItemById(mergedItemId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(mergedItemId))

            val survivor = survivorEntity.toDomainModel()
            val merged = mergedEntity.toDomainModel()

            val survivorRuleBefore = stapleRuleDao.getStapleRuleByCanonicalIngredientId(getCanonicalId(survivor))
            val mergedRuleBefore = stapleRuleDao.getStapleRuleByCanonicalIngredientId(getCanonicalId(merged))

            val survivorBarcodesBefore = inventoryBarcodeDao.getBarcodesForItem(survivorItemId)
            val mergedBarcodes = inventoryBarcodeDao.getBarcodesForItem(mergedItemId)

            // Transfer barcodes from merged item to survivor
            inventoryBarcodeDao.transferBarcodes(oldInventoryItemId = mergedItemId, newInventoryItemId = survivorItemId)
            val survivorBarcodesAfter = inventoryBarcodeDao.getBarcodesForItem(survivorItemId)

            // Consolidate stock
            val newQty = (survivor.quantity ?: 0.0) + (merged.quantity ?: 0.0)
            val newPackCount = (survivor.packCount ?: 0) + (merged.packCount ?: 0)
            val mergedStapleState = survivor.staple || merged.staple
            val finalName = preferredDisplayName?.ifBlank { null } ?: survivor.displayName

            val updatedSurvivor = survivor.copy(
                displayName = finalName,
                quantity = newQty,
                packCount = newPackCount,
                staple = mergedStapleState,
                status = ItemStatus.AVAILABLE,
                updatedAt = System.currentTimeMillis(),
                revision = survivor.revision + 1L
            )
            inventoryDao.updateItem(updatedSurvivor.toEntity())

            // Mark obsolete merged item's StapleRule as archivedByMerge = true, PRESERVING its original user enabled preference!
            if (mergedRuleBefore != null) {
                stapleRuleDao.insertOrUpdateStapleRule(
                    mergedRuleBefore.copy(
                        archivedByMerge = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            // Sync/update single resulting StapleRule for survivor item
            if (mergedStapleState) {
                syncStapleRuleForItem(updatedSurvivor, true)
                val resultingRule = stapleRuleDao.getStapleRuleByCanonicalIngredientId(getCanonicalId(updatedSurvivor))
                if (resultingRule != null) {
                    stapleRuleDao.insertOrUpdateStapleRule(
                        resultingRule.copy(
                            archivedByMerge = false,
                            thresholdQuantity = preferredRuleThreshold ?: resultingRule.thresholdQuantity,
                            targetQuantity = preferredRuleTarget ?: resultingRule.targetQuantity,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            // Mark merged item as DISCARDED / MERGED_OUT
            inventoryDao.updateItem(merged.copy(status = ItemStatus.DISCARDED, quantity = 0.0, packCount = 0, updatedAt = System.currentTimeMillis(), revision = merged.revision + 1L).toEntity())

            // Preserve merge log snapshot with full mergedRule snapshot for unmerge provenance!
            val mergeLog = InventoryMergeLogEntity(
                id = UUID.randomUUID().toString(),
                survivorItemId = survivorItemId,
                mergedItemId = mergedItemId,
                mergedItemSnapshotJson = json.encodeToString(merged),
                mergedRuleSnapshotJson = mergedRuleBefore?.let { json.encodeToString(it) },
                transferredBarcodesJson = json.encodeToString(mergedBarcodes.map { it.rawBarcode }),
                transferredQuantity = merged.quantity ?: 0.0,
                transferredPackCount = merged.packCount ?: 0,
                mergedAt = System.currentTimeMillis(),
                unmerged = false
            )
            inventoryBarcodeDao.insertMergeLog(mergeLog)

            val finalResultingRule = stapleRuleDao.getStapleRuleByCanonicalIngredientId(getCanonicalId(updatedSurvivor))

            safeLog("[MERGE_STAPLE_AUDIT] survivorItemId=$survivorItemId, survivorName=\"${survivor.displayName}\", survivorCanonicalId=${getCanonicalId(survivor)}, survivorRuleId=${survivorRuleBefore?.id}, survivorRuleEnabled=${survivorRuleBefore?.enabled}, mergedItemId=$mergedItemId, mergedName=\"${merged.displayName}\", mergedCanonicalId=${getCanonicalId(merged)}, mergedRuleId=${mergedRuleBefore?.id}, mergedRuleArchived=true, resultingRuleId=${finalResultingRule?.id}, resultingRuleEnabled=${finalResultingRule?.enabled}")
            safeLog("[INVENTORY_MERGE] sourceIds=[\"$survivorItemId\", \"$mergedItemId\"], survivorId=$survivorItemId, barcodeCountBefore=${survivorBarcodesBefore.size}, barcodeCountAfter=${survivorBarcodesAfter.size}, quantityDecision=COMBINED, stapleDecision=$mergedStapleState, ruleDecision=PRESERVED_SINGLE_ACTIVE_RULE")

            DomainResult.Success(updatedSurvivor)
        }
    }

    override suspend fun unmergeInventoryItems(
        mergeLogId: String,
        survivorStockAllocation: Double?,
        restoredStockAllocation: Double?
    ): DomainResult<Pair<InventoryItem, InventoryItem>> {
        return transactionRunner.runInTransaction {
            val log = inventoryBarcodeDao.getMergeLogById(mergeLogId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound("Merge log $mergeLogId not found."))

            if (log.unmerged) {
                return@runInTransaction DomainResult.Failure(DomainError.DuplicateOperation("Merge $mergeLogId has already been unmerged."))
            }

            val survivorEntity = inventoryDao.getItemById(log.survivorItemId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(log.survivorItemId))

            val mergedSnapshot = json.decodeFromString<InventoryItem>(log.mergedItemSnapshotJson)
            val transferredBarcodes = json.decodeFromString<List<String>>(log.transferredBarcodesJson)

            val survivor = survivorEntity.toDomainModel()
            val expectedPostMergeTotal = (survivor.quantity ?: 0.0)

            // Check if stock HAS changed since merge
            val stockHasChangedSinceMerge = survivorStockAllocation != null || restoredStockAllocation != null || Math.abs(expectedPostMergeTotal - log.transferredQuantity) > 0.01

            val (finalSurvivorQty, finalRestoredQty) = if (stockHasChangedSinceMerge && survivorStockAllocation != null && restoredStockAllocation != null) {
                Pair(survivorStockAllocation, restoredStockAllocation)
            } else {
                val automaticSurvivorQty = maxOf(0.0, (survivor.quantity ?: 0.0) - log.transferredQuantity)
                val automaticRestoredQty = log.transferredQuantity
                Pair(automaticSurvivorQty, automaticRestoredQty)
            }

            // Restore merged item to inventory
            val restoredMerged = mergedSnapshot.copy(
                quantity = finalRestoredQty,
                status = ItemStatus.AVAILABLE,
                updatedAt = System.currentTimeMillis()
            )
            inventoryDao.insertItem(restoredMerged.toEntity())

            // Restore merged item's original StapleRule from snapshot if present (un-archiving and restoring exact original enabled preference!)
            if (!log.mergedRuleSnapshotJson.isNullOrBlank()) {
                val restoredRule = json.decodeFromString<StapleRuleEntity>(log.mergedRuleSnapshotJson)
                stapleRuleDao.insertOrUpdateStapleRule(restoredRule.copy(archivedByMerge = false, enabled = restoredRule.enabled, updatedAt = System.currentTimeMillis()))
            } else if (restoredMerged.staple) {
                syncStapleRuleForItem(restoredMerged, true)
            }

            // Re-assign transferred barcodes back to restored merged item
            transferredBarcodes.forEach { rawBc ->
                val normBc = normalizeBarcode(rawBc)
                if (normBc != null) {
                    val bcEntity = inventoryBarcodeDao.getBarcodeEntity(normBc)
                    if (bcEntity != null) {
                        inventoryBarcodeDao.updateBarcodeOwner(bcEntity.id, restoredMerged.id)
                    }
                }
            }

            // Adjust survivor quantity back
            val updatedSurvivor = survivor.copy(
                quantity = finalSurvivorQty,
                updatedAt = System.currentTimeMillis()
            )
            inventoryDao.updateItem(updatedSurvivor.toEntity())

            // Mark merge log as unmerged
            inventoryBarcodeDao.updateMergeLog(log.copy(unmerged = true))

            safeLog("[INVENTORY_UNMERGE] mergeId=$mergeLogId, restoredItemIds=[\"${restoredMerged.id}\"], barcodeAssignments=RESTORED, stockDecision=RESTORED, result=SUCCESS")

            DomainResult.Success(Pair(updatedSurvivor, restoredMerged))
        }
    }

    override suspend fun repairOrphanStapleRulesFromMergeLogs(): DomainResult<Int> {
        return transactionRunner.runInTransaction {
            val activeMergeLogs = inventoryBarcodeDao.getActiveMergeLogs()
            var archivedCount = 0

            activeMergeLogs.forEach { log ->
                val mergedItemId = log.mergedItemId
                val candidateCanonicalIds = listOf(
                    mergedItemId,
                    "item_$mergedItemId",
                    "item_item_$mergedItemId"
                )

                candidateCanonicalIds.forEach { candidateId ->
                    val rule = stapleRuleDao.getStapleRuleByCanonicalIngredientId(candidateId)
                    if (rule != null && !rule.archivedByMerge) {
                        stapleRuleDao.insertOrUpdateStapleRule(rule.copy(archivedByMerge = true, updatedAt = System.currentTimeMillis()))
                        archivedCount++
                        safeLog("[STAPLE_ORPHAN_REPAIR] mergeLogId=${log.id}, survivorItemId=${log.survivorItemId}, mergedItemId=$mergedItemId, sourceRuleId=${rule.id}, sourceRuleOriginalEnabled=${rule.enabled}, action=ARCHIVED_BY_MERGE, reason=MERGED_SOURCE_ITEM_DISCARDED")
                    }
                }
            }

            // Also check any staple rules where the inventory item is explicitly DISCARDED
            val discardedItems = inventoryDao.getAllItemsSync().filter { it.status == ItemStatus.DISCARDED }
            discardedItems.forEach { discardedItem ->
                val candidateIds = listOf(
                    discardedItem.id,
                    "item_${discardedItem.id}",
                    discardedItem.canonicalIngredientId ?: ""
                ).filter { it.isNotBlank() }

                candidateIds.forEach { cId ->
                    val rule = stapleRuleDao.getStapleRuleByCanonicalIngredientId(cId)
                    if (rule != null && !rule.archivedByMerge) {
                        stapleRuleDao.insertOrUpdateStapleRule(rule.copy(archivedByMerge = true, updatedAt = System.currentTimeMillis()))
                        archivedCount++
                        safeLog("[STAPLE_ORPHAN_REPAIR] discardedItemId=${discardedItem.id}, sourceRuleId=${rule.id}, sourceRuleOriginalEnabled=${rule.enabled}, action=ARCHIVED_BY_MERGE, reason=ITEM_DISCARDED")
                    }
                }
            }

            safeLog("[STAPLE_ORPHAN_REPAIR_SUMMARY] mergeLogsChecked=${activeMergeLogs.size}, rulesArchived=$archivedCount")
            DomainResult.Success(archivedCount)
        }
    }

    override suspend fun getAssociatedBarcodes(inventoryItemId: String): List<String> {
        return inventoryBarcodeDao.getBarcodesForItem(inventoryItemId).map { it.rawBarcode }
    }

    override suspend fun associateBarcode(inventoryItemId: String, rawBarcode: String): DomainResult<Unit> {
        val normBarcode = normalizeBarcode(rawBarcode)
            ?: return DomainResult.Failure(DomainError.InvalidQuantity(0.0))

        return transactionRunner.runInTransaction {
            val item = inventoryDao.getItemById(inventoryItemId)
                ?: return@runInTransaction DomainResult.Failure(DomainError.ItemNotFound(inventoryItemId))

            val existingBarcode = inventoryBarcodeDao.getBarcodeEntity(normBarcode)
            if (existingBarcode != null && existingBarcode.inventoryItemId != inventoryItemId) {
                return@runInTransaction DomainResult.Failure(
                    DomainError.DuplicateOperation("Barcode $rawBarcode is already associated with another inventory item.")
                )
            }

            if (existingBarcode == null) {
                inventoryBarcodeDao.insertBarcode(
                    InventoryItemBarcodeEntity(
                        id = UUID.randomUUID().toString(),
                        inventoryItemId = inventoryItemId,
                        rawBarcode = rawBarcode,
                        normalizedBarcode = normBarcode,
                        addedAt = System.currentTimeMillis(),
                        source = "MANUAL_ASSOCIATION"
                    )
                )
            }

            if (item.barcode.isNullOrBlank()) {
                inventoryDao.updateItem(item.copy(barcode = normBarcode))
            }

            DomainResult.Success(Unit)
        }
    }

    override suspend fun removeBarcodeAssociation(inventoryItemId: String, rawBarcode: String): DomainResult<Unit> {
        val normBarcode = normalizeBarcode(rawBarcode)
            ?: return DomainResult.Failure(DomainError.InvalidQuantity(0.0))

        return transactionRunner.runInTransaction {
            val bcEntity = inventoryBarcodeDao.getBarcodeEntity(normBarcode)
            if (bcEntity != null && bcEntity.inventoryItemId == inventoryItemId) {
                inventoryBarcodeDao.deleteBarcode(bcEntity.id)
            }
            DomainResult.Success(Unit)
        }
    }

    private suspend fun syncStapleRuleForItem(item: InventoryItem, isStaple: Boolean): Boolean {
        val newCanonicalId = getCanonicalId(item)
        val fallbackId = "item_${item.id}"

        // Handle fallback rule migration if canonical ID was previously fallback and now became a real canonical ID
        val oldFallbackRule = if (newCanonicalId != fallbackId) stapleRuleDao.getStapleRuleByCanonicalIngredientId(fallbackId) else null
        if (oldFallbackRule != null) {
            val migratedRule = oldFallbackRule.copy(
                canonicalIngredientId = newCanonicalId,
                displayName = item.displayName,
                enabled = isStaple,
                archivedByMerge = false,
                updatedAt = System.currentTimeMillis()
            )
            stapleRuleDao.deleteStapleRule(oldFallbackRule.id)
            stapleRuleDao.insertOrUpdateStapleRule(migratedRule)
            safeLog("[STAPLE_IDENTITY_MIGRATION] oldRuleId=${oldFallbackRule.id}, newCanonicalId=$newCanonicalId, decision=MIGRATED_FALLBACK_RULE")
            return true
        }

        val existingRule = stapleRuleDao.getStapleRuleByCanonicalIngredientId(newCanonicalId)

        if (existingRule != null) {
            val updatedRule = existingRule.copy(enabled = isStaple, archivedByMerge = false, updatedAt = System.currentTimeMillis())
            stapleRuleDao.insertOrUpdateStapleRule(updatedRule)
            safeLog("[STAPLE_RULE_WRITE] itemId=${item.id}, ruleId=${updatedRule.id}, mode=${updatedRule.mode}, enabled=$isStaple, result=UPDATED_EXISTING")
            return true
        } else if (isStaple) {
            val unit = item.quantityUnit ?: QuantityUnit.GRAM
            val isPackUnit = unit in listOf(QuantityUnit.PACK, QuantityUnit.TIN, QuantityUnit.BOTTLE, QuantityUnit.BAG, QuantityUnit.UNIT, QuantityUnit.SLICE)

            val (mode, threshQty, targetQty, threshPacks, targetPacks) = if (isPackUnit) {
                Tuple5(StapleRuleMode.PACK_COUNT, null, null, 1, 4)
            } else {
                val thresh = when (unit) {
                    QuantityUnit.KILOGRAM -> 0.2
                    QuantityUnit.LITRE -> 0.2
                    else -> 200.0
                }
                val target = when (unit) {
                    QuantityUnit.KILOGRAM -> 0.5
                    QuantityUnit.LITRE -> 1.0
                    else -> 500.0
                }
                Tuple5(StapleRuleMode.QUANTITY, thresh, target, null, null)
            }

            val newRule = StapleRuleEntity(
                id = UUID.randomUUID().toString(),
                canonicalIngredientId = newCanonicalId,
                displayName = item.displayName,
                enabled = true,
                archivedByMerge = false,
                mode = mode,
                thresholdQuantity = threshQty,
                thresholdUnit = unit,
                targetQuantity = targetQty,
                targetUnit = unit,
                thresholdPackCount = threshPacks,
                targetPackCount = targetPacks,
                preferredStorageLocation = item.storageLocation,
                notes = "Auto-created staple rule from inventory item",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            stapleRuleDao.insertOrUpdateStapleRule(newRule)
            safeLog("[STAPLE_RULE_WRITE] itemId=${item.id}, ruleId=${newRule.id}, mode=${newRule.mode}, enabled=true, result=CREATED_NEW")
            return true
        }
        return false
    }

    private fun getCanonicalId(item: InventoryItem): String {
        return item.canonicalIngredientId.takeIf { !it.isNullOrBlank() } ?: "item_${item.id}"
    }

    private fun safeLog(msg: String) {
        try {
            Log.d("INVENTORY_IDENTITY_TRACE", msg)
        } catch (ignored: Throwable) {}
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
