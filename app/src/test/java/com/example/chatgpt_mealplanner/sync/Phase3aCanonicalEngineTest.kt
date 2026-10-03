package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.sync.engine.MainCanonicalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.AddItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.AdjustQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.EditItemPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.RequestDiscardPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SetQuantityPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

class FakeCanonicalSyncDao : CanonicalSyncDao {
    private val opsMap = mutableMapOf<String, CanonicalSyncOperationEntity>()
    private val seqMap = mutableMapOf<String, CanonicalSyncOperationEntity>()
    private val conflictsMap = mutableMapOf<String, SyncConflictEntity>()
    private val approvalsMap = mutableMapOf<String, SyncPendingApprovalEntity>()
    private var sequenceEntity: CanonicalSyncSequenceEntity? = null

    override suspend fun getByOperationId(operationId: String): CanonicalSyncOperationEntity? = opsMap[operationId]

    override suspend fun getByDeviceSequence(deviceId: String, deviceSequence: Long): CanonicalSyncOperationEntity? = seqMap["${deviceId}_$deviceSequence"]

    override suspend fun insertCanonicalOperation(op: CanonicalSyncOperationEntity) {
        opsMap[op.operationId] = op
        seqMap["${op.deviceId}_${op.deviceSequence}"] = op
    }

    override suspend fun insertConflict(conflict: SyncConflictEntity) {
        conflictsMap[conflict.operationId] = conflict
    }

    override suspend fun insertPendingApproval(approval: SyncPendingApprovalEntity) {
        approvalsMap[approval.operationId] = approval
    }

    override suspend fun getSequenceEntity(): CanonicalSyncSequenceEntity? = sequenceEntity

    override suspend fun updateSequenceEntity(entity: CanonicalSyncSequenceEntity) {
        sequenceEntity = entity
    }

    override suspend fun getConflictByOperationId(operationId: String): SyncConflictEntity? = conflictsMap[operationId]

    override suspend fun getApprovalByOperationId(operationId: String): SyncPendingApprovalEntity? = approvalsMap[operationId]
}

@RunWith(JUnit4::class)
class Phase3aCanonicalEngineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var canonicalSyncDao: FakeCanonicalSyncDao
    private lateinit var pairedDeviceDao: FakeSyncPairedDeviceDao
    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var transactionRunner: FakeTransactionRunner
    private lateinit var engine: MainCanonicalSyncEngine

    private val activeDeviceId = UUID.randomUUID().toString()

    @Before
    fun setUp() = runTest {
        canonicalSyncDao = FakeCanonicalSyncDao()
        pairedDeviceDao = FakeSyncPairedDeviceDao()
        inventoryDao = FakeInventoryDao()
        transactionRunner = FakeTransactionRunner()

        engine = MainCanonicalSyncEngine(
            canonicalSyncDao = canonicalSyncDao,
            pairedDeviceDao = pairedDeviceDao,
            inventoryDao = inventoryDao,
            transactionRunner = transactionRunner
        )

        pairedDeviceDao.insertOrUpdateDevice(
            PairedDeviceEntity(
                deviceId = activeDeviceId,
                deviceName = "Roommate Phone",
                devicePublicKey = "MCowBQYDK2Vw...",
                status = "ACTIVE",
                pairedAt = System.currentTimeMillis(),
                lastSyncTimestamp = System.currentTimeMillis()
            )
        )
    }

    @Test
    fun addItem_startsSequenceAtOne_andCreatesLotWithRevisionOne() = runTest {
        val itemId = UUID.randomUUID().toString()
        val addPayload = AddItemPayloadDto(
            itemId = itemId,
            canonicalName = "Milk",
            displayName = "Whole Milk",
            category = "FRESH_PANTRY",
            storageLocation = "FRIDGE",
            quantity = 2.0,
            quantityUnit = "LITRE",
            barcode = "5000159461122"
        )

        val opDto = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = activeDeviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.ADD_ITEM,
            payloadJson = json.encodeToString(addPayload),
            createdAt = System.currentTimeMillis()
        )

        val result = engine.processOperation(activeDeviceId, opDto)

        assertEquals(SyncOperationStatus.APPLIED, result.status)
        assertEquals(1L, result.canonicalSequence) // First canonical sequence MUST BE 1
        assertEquals(1L, result.resultingRevision) // Revision MUST BE 1L

        val insertedItem = inventoryDao.getItemById(itemId)
        assertNotNull(insertedItem)
        assertEquals("Whole Milk", insertedItem?.displayName)
        assertEquals(1L, insertedItem?.revision)
    }

    @Test
    fun sameBarcode_independentAdds_createTwoDistinctLots() = runTest {
        val itemIdA = UUID.randomUUID().toString()
        val itemIdB = UUID.randomUUID().toString()

        val addA = AddItemPayloadDto(itemId = itemIdA, canonicalName = "Milk", displayName = "Milk Lot A", category = "FRESH_PANTRY", storageLocation = "FRIDGE", barcode = "5000159461122")
        val addB = AddItemPayloadDto(itemId = itemIdB, canonicalName = "Milk", displayName = "Milk Lot B", category = "FRESH_PANTRY", storageLocation = "FRIDGE", barcode = "5000159461122")

        val opA = SyncOperationDto(operationId = UUID.randomUUID().toString(), deviceId = activeDeviceId, deviceSequence = 1L, operationType = SyncOperationType.ADD_ITEM, payloadJson = json.encodeToString(addA), createdAt = System.currentTimeMillis())
        val opB = SyncOperationDto(operationId = UUID.randomUUID().toString(), deviceId = activeDeviceId, deviceSequence = 2L, operationType = SyncOperationType.ADD_ITEM, payloadJson = json.encodeToString(addB), createdAt = System.currentTimeMillis())

        val resA = engine.processOperation(activeDeviceId, opA)
        val resB = engine.processOperation(activeDeviceId, opB)

        assertEquals(1L, resA.canonicalSequence)
        assertEquals(2L, resB.canonicalSequence)

        assertNotNull(inventoryDao.getItemById(itemIdA))
        assertNotNull(inventoryDao.getItemById(itemIdB))
    }

    @Test
    fun editItem_matchingRevision_appliesAndIncrementsRevision() = runTest {
        val itemId = UUID.randomUUID().toString()
        inventoryDao.insertItem(
            InventoryItemEntity(
                id = itemId,
                canonicalName = "Bread",
                displayName = "White Bread",
                category = FoodCategory.BREAD_BAKERY,
                storageLocation = StorageLocation.CUPBOARD,
                quantityConfidence = QuantityConfidence.EXACT,
                status = ItemStatus.AVAILABLE,
                frozen = false,
                staple = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                revision = 1L
            )
        )

        val editPayload = EditItemPayloadDto(displayName = "Sliced Sourdough Bread")
        val opDto = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = activeDeviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.EDIT_ITEM,
            targetItemId = itemId,
            baseRevision = 1L, // Matching base revision
            payloadJson = json.encodeToString(editPayload),
            createdAt = System.currentTimeMillis()
        )

        val result = engine.processOperation(activeDeviceId, opDto)

        assertEquals(SyncOperationStatus.APPLIED, result.status)
        assertEquals(2L, result.resultingRevision)

        val updated = inventoryDao.getItemById(itemId)
        assertEquals("Sliced Sourdough Bread", updated?.displayName)
        assertEquals(2L, updated?.revision)
    }

    @Test
    fun editItem_staleRevision_stagesConflictAndDoesNotMutateInventory() = runTest {
        val itemId = UUID.randomUUID().toString()
        inventoryDao.insertItem(
            InventoryItemEntity(
                id = itemId,
                canonicalName = "Juice",
                displayName = "Orange Juice",
                category = FoodCategory.FRESH_PANTRY,
                storageLocation = StorageLocation.FRIDGE,
                quantity = 1.0,
                quantityConfidence = QuantityConfidence.EXACT,
                status = ItemStatus.AVAILABLE,
                frozen = false,
                staple = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                revision = 5L // Current revision on Main is 5
            )
        )

        val editPayload = EditItemPayloadDto(displayName = "Stale Edited Juice")
        val opDto = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = activeDeviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.EDIT_ITEM,
            targetItemId = itemId,
            baseRevision = 2L, // Stale base revision (2 != 5)
            payloadJson = json.encodeToString(editPayload),
            createdAt = System.currentTimeMillis()
        )

        val result = engine.processOperation(activeDeviceId, opDto)

        assertEquals(SyncOperationStatus.CONFLICT, result.status)
        assertNotNull(result.conflictId)

        // Verify inventory item remained 100% UNMUTATED
        val itemAfter = inventoryDao.getItemById(itemId)
        assertEquals("Orange Juice", itemAfter?.displayName)
        assertEquals(5L, itemAfter?.revision)
    }

    @Test
    fun requestDiscard_matchingRevision_createsPendingApprovalWithoutDiscarding() = runTest {
        val itemId = UUID.randomUUID().toString()
        inventoryDao.insertItem(
            InventoryItemEntity(
                id = itemId,
                canonicalName = "Cheese",
                displayName = "Cheddar Cheese",
                category = FoodCategory.FRESH_PANTRY,
                storageLocation = StorageLocation.FRIDGE,
                quantity = 1.0,
                quantityConfidence = QuantityConfidence.EXACT,
                status = ItemStatus.AVAILABLE,
                frozen = false,
                staple = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                revision = 1L
            )
        )

        val discardPayload = RequestDiscardPayloadDto(reason = "Moldy")
        val opDto = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = activeDeviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM,
            targetItemId = itemId,
            baseRevision = 1L,
            payloadJson = json.encodeToString(discardPayload),
            createdAt = System.currentTimeMillis()
        )

        val result = engine.processOperation(activeDeviceId, opDto)

        assertEquals(SyncOperationStatus.PENDING_APPROVAL, result.status)
        assertNotNull(result.approvalId)

        // Item status MUST REMAIN AVAILABLE in DB (not discarded!)
        val itemAfter = inventoryDao.getItemById(itemId)
        assertEquals(ItemStatus.AVAILABLE, itemAfter?.status)
        assertEquals(1L, itemAfter?.revision)
    }

    @Test
    fun requestDiscard_staleRevision_createsConflict_andNoApproval() = runTest {
        val itemId = UUID.randomUUID().toString()
        inventoryDao.insertItem(
            InventoryItemEntity(
                id = itemId,
                canonicalName = "Butter",
                displayName = "Salted Butter",
                category = FoodCategory.FRESH_PANTRY,
                storageLocation = StorageLocation.FRIDGE,
                quantity = 1.0,
                quantityConfidence = QuantityConfidence.EXACT,
                status = ItemStatus.AVAILABLE,
                frozen = false,
                staple = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                revision = 3L
            )
        )

        val discardPayload = RequestDiscardPayloadDto(reason = "Moldy")
        val opDto = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = activeDeviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM,
            targetItemId = itemId,
            baseRevision = 1L, // Stale revision (1 != 3)
            payloadJson = json.encodeToString(discardPayload),
            createdAt = System.currentTimeMillis()
        )

        val result = engine.processOperation(activeDeviceId, opDto)

        assertEquals(SyncOperationStatus.CONFLICT, result.status)
        assertNotNull(result.conflictId)
        assertNull("No approval should be created for stale discard request", result.approvalId)

        // Item status MUST REMAIN AVAILABLE in DB
        val itemAfter = inventoryDao.getItemById(itemId)
        assertEquals(ItemStatus.AVAILABLE, itemAfter?.status)
        assertEquals(3L, itemAfter?.revision)
    }

    @Test
    fun targetNotFound_returnsRejected() = runTest {
        val nonExistentItemId = UUID.randomUUID().toString()
        val editPayload = EditItemPayloadDto(displayName = "Ghost Item")
        val opDto = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = activeDeviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.EDIT_ITEM,
            targetItemId = nonExistentItemId,
            baseRevision = 1L,
            payloadJson = json.encodeToString(editPayload),
            createdAt = System.currentTimeMillis()
        )

        val result = engine.processOperation(activeDeviceId, opDto)

        assertEquals(SyncOperationStatus.REJECTED, result.status)
        assertEquals("TARGET_NOT_FOUND", result.resultCode)
    }

    @Test
    fun adjustQuantity_negativeResult_isRejected_andMainQuantityAndRevisionUnchanged() = runTest {
        val itemId = UUID.randomUUID().toString()
        inventoryDao.insertItem(
            InventoryItemEntity(
                id = itemId,
                canonicalName = "Milk",
                displayName = "Whole Milk",
                category = FoodCategory.FRESH_PANTRY,
                storageLocation = StorageLocation.FRIDGE,
                quantity = 2.0,
                quantityConfidence = QuantityConfidence.EXACT,
                status = ItemStatus.AVAILABLE,
                frozen = false,
                staple = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                revision = 1L
            )
        )

        val adjustPayload = AdjustQuantityPayloadDto(delta = -10.0)
        val opDto = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = activeDeviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.ADJUST_QUANTITY,
            targetItemId = itemId,
            baseRevision = 1L,
            payloadJson = json.encodeToString(adjustPayload),
            createdAt = System.currentTimeMillis()
        )

        val result = engine.processOperation(activeDeviceId, opDto)

        assertEquals(SyncOperationStatus.REJECTED, result.status)
        assertEquals("INVALID_QUANTITY", result.resultCode)

        val itemAfter = inventoryDao.getItemById(itemId)
        assertEquals(2.0, itemAfter?.quantity ?: 0.0, 0.001)
        assertEquals(1L, itemAfter?.revision)
    }

    @Test
    fun createdAt_farInFutureOrPast_doesNotControlCanonicalSequenceOrdering() = runTest {
        val itemIdA = UUID.randomUUID().toString()
        val itemIdB = UUID.randomUUID().toString()

        val addA = AddItemPayloadDto(itemId = itemIdA, canonicalName = "Apples", displayName = "Red Apples", category = "FRESH_PANTRY", storageLocation = "FRIDGE")
        val addB = AddItemPayloadDto(itemId = itemIdB, canonicalName = "Bananas", displayName = "Yellow Bananas", category = "FRESH_PANTRY", storageLocation = "FRIDGE")

        val opA = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = activeDeviceId,
            deviceSequence = 1L,
            operationType = SyncOperationType.ADD_ITEM,
            payloadJson = json.encodeToString(addA),
            createdAt = 4070908800000L
        )

        val opB = SyncOperationDto(
            operationId = UUID.randomUUID().toString(),
            deviceId = activeDeviceId,
            deviceSequence = 2L,
            operationType = SyncOperationType.ADD_ITEM,
            payloadJson = json.encodeToString(addB),
            createdAt = 946684800000L
        )

        val resA = engine.processOperation(activeDeviceId, opA)
        val resB = engine.processOperation(activeDeviceId, opB)

        assertEquals(1L, resA.canonicalSequence)
        assertEquals(2L, resB.canonicalSequence)
    }
}
