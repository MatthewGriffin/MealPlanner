package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryTransactionEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.model.TransactionType
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.sync.AppRole
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.repository.ResolutionResult
import com.example.chatgpt_mealplanner.core.sync.repository.SyncConflictApprovalRepository
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.DuplicateDiscardRequestException
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.RequestDiscardPayloadDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class PantrySyncPhase4gDiscardSyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class TestPairingStore(private val paired: Boolean = false) : FollowerPairingStore(null) {
        override fun isPaired(): Boolean = paired
    }

    private fun createSampleItemEntity(
        id: String = "item-disc-1",
        quantity: Double? = 2.0,
        status: ItemStatus = ItemStatus.AVAILABLE,
        revision: Long = 1L
    ): InventoryItemEntity = InventoryItemEntity(
        id = id,
        barcode = null,
        canonicalIngredientId = null,
        canonicalName = "olive oil",
        displayName = "Olive Oil",
        brand = "Filippo Berio",
        category = FoodCategory.CUPBOARD,
        storageLocation = StorageLocation.CUPBOARD,
        quantity = quantity,
        quantityUnit = QuantityUnit.LITRE,
        packCount = null,
        packSize = null,
        packSizeUnit = null,
        quantityConfidence = QuantityConfidence.EXACT,
        status = status,
        opened = null,
        frozen = false,
        staple = false,
        purchaseDate = null,
        openedDate = null,
        useByDate = null,
        bestBeforeDate = null,
        notes = null,
        createdAt = 1000L,
        updatedAt = 1000L,
        revision = revision
    )

    @Test
    fun testDiscardItem_onMain_atomicallyUpdatesItemAndPublishesCanonicalOperation() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        val txs = mutableListOf<InventoryTransactionEntity>()
        var seqEntity: CanonicalSyncSequenceEntity? = null

        val sample = createSampleItemEntity(quantity = 2.0, revision = 1L)
        itemsMap[sample.id] = sample

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { updateItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[item.id] = item
                Unit
            }
        }

        val canonicalSyncDao: CanonicalSyncDao = mock {
            onBlocking { getSequenceEntity() } doAnswer { seqEntity }
            onBlocking { updateSequenceEntity(any()) } doAnswer { invocation ->
                seqEntity = invocation.getArgument(0)
                Unit
            }
            onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                ops.add(invocation.getArgument(0))
                Unit
            }
        }

        val transactionDao: TransactionDao = mock {
            onBlocking { insertTransaction(any()) } doAnswer { invocation ->
                txs.add(invocation.getArgument(0))
                Unit
            }
        }

        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer {
                HouseholdIdentityEntity("house-1", "Main Household", "main-device-100", System.currentTimeMillis())
            }
        }

        val appRoleResolver = object : AppRoleResolver(TestPairingStore(), identityDao) {
            override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
            override suspend fun isMainAsync(): Boolean = true
            override fun getAppRole(): AppRole = AppRole.MAIN
            override fun isMain(): Boolean = true
        }

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = transactionDao,
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = appRoleResolver,
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val result = domainService.discardItem("item-disc-1", reason = "Spoiled")

        assertTrue(result is DomainResult.Success)

        // 1. Item updated on Main with quantity 0.0, status DISCARDED, revision 2
        val saved = itemsMap["item-disc-1"]
        assertNotNull(saved)
        assertEquals(0.0, saved!!.quantity!!, 0.001)
        assertEquals(ItemStatus.DISCARDED, saved.status)
        assertEquals(2L, saved.revision)

        // 2. Exactly 1 canonical REQUEST_DISCARD_ITEM operation emitted with resultCode APPROVED_DISCARD
        assertEquals(1, ops.size)
        val op = ops[0]
        assertEquals(SyncOperationType.REQUEST_DISCARD_ITEM.name, op.operationType)
        assertEquals("APPROVED_DISCARD", op.resultCode)
        assertEquals("item-disc-1", op.targetItemId)
        assertEquals(1L, op.baseRevision)

        val payload = json.decodeFromString<RequestDiscardPayloadDto>(op.payloadJson)
        assertEquals("Spoiled", payload.reason)

        // 3. Inventory transaction logged
        assertEquals(1, txs.size)
        assertEquals(TransactionType.DISCARDED, txs[0].type)
    }

    @Test
    fun testDiscardItem_onFollower_queuesRequestDiscardOutboxAndNoMainCanonicalOperation() = runTest {
        val followerItemsMap = mutableMapOf<String, FollowerInventoryItemEntity>()
        val followerOutbox = mutableListOf<FollowerSyncOutboxEntity>()
        val mainOps = mutableListOf<CanonicalSyncOperationEntity>()

        val sampleFollowerItem = FollowerInventoryItemEntity(
            itemId = "item-disc-1",
            barcode = null,
            canonicalName = "olive oil",
            confirmedDisplayName = "Olive Oil",
            confirmedBrand = "Filippo Berio",
            confirmedCategory = "CUPBOARD",
            confirmedStorageLocation = "CUPBOARD",
            confirmedQuantity = 2.0,
            confirmedQuantityUnit = "LITRE",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "Olive Oil",
            brand = "Filippo Berio",
            category = "CUPBOARD",
            storageLocation = "CUPBOARD",
            quantity = 2.0,
            quantityUnit = "LITRE",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = false,
            updatedAt = 1000L
        )
        followerItemsMap[sampleFollowerItem.itemId] = sampleFollowerItem

        val followerDao: FollowerDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                followerItemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getUnresolvedOutboxOpsForTargetItem(any()) } doAnswer { emptyList() }
        }

        val localSyncEngine = object : FollowerLocalSyncEngine(null) {
            override suspend fun recordLocalRequestDiscard(
                deviceId: String,
                targetItemId: String,
                reason: String?
            ): FollowerSyncOutboxEntity {
                val outboxEntity = FollowerSyncOutboxEntity(
                    operationId = "op-disc-888",
                    deviceId = deviceId,
                    deviceSequence = 1L,
                    protocolVersion = 1,
                    operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
                    targetItemId = targetItemId,
                    baseRevision = 1L,
                    payloadJson = json.encodeToString(RequestDiscardPayloadDto(reason = reason)),
                    createdAt = 1000L,
                    status = "PENDING",
                    createdLocallyAt = 1000L
                )
                followerOutbox.add(outboxEntity)
                val item = followerItemsMap[targetItemId]
                if (item != null) {
                    followerItemsMap[targetItemId] = item.copy(status = "DISCARDED", isPendingSync = true)
                }
                return outboxEntity
            }
        }

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = mock(),
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(paired = true)) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.FOLLOWER
                override suspend fun isMainAsync(): Boolean = false
            },
            canonicalSyncDao = mock {
                onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                    mainOps.add(invocation.getArgument(0))
                    Unit
                }
            },
            householdIdentityManager = HouseholdIdentityManager(mock()),
            followerDatabase = object : FollowerDatabase() {
                override fun followerDao(): FollowerDao = followerDao
                override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
                override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
                override fun clearAllTables() {}
                override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
                @Deprecated("Deprecated in Room")
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
            },
            followerLocalSyncEngine = localSyncEngine,
            followerIdentityStore = object : FollowerIdentityStore(null) {
                override fun getOrCreateDeviceId(): String = "follower-device-999"
            }
        )

        val result = domainService.discardItem("item-disc-1", reason = "Spoiled")

        assertTrue(result is DomainResult.Success)

        // 1. Zero Main canonical operations emitted
        assertEquals(0, mainOps.size)

        // 2. Exactly 1 Follower outbox REQUEST_DISCARD_ITEM operation created
        assertEquals(1, followerOutbox.size)
        val ob = followerOutbox[0]
        assertEquals(SyncOperationType.REQUEST_DISCARD_ITEM.name, ob.operationType)
        assertEquals("item-disc-1", ob.targetItemId)
        assertEquals(1L, ob.baseRevision)
        assertEquals("PENDING", ob.status)

        // 3. Optimistic working status on Follower becomes DISCARDED
        val updated = followerItemsMap["item-disc-1"]
        assertNotNull(updated)
        assertEquals("DISCARDED", updated!!.status)
        assertEquals(true, updated.isPendingSync)
    }

    @Test
    fun testDiscardItem_alreadyDiscarded_returnsSuccessWithoutDuplicateOperation() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val sample = createSampleItemEntity(status = ItemStatus.DISCARDED, revision = 2L)
        itemsMap[sample.id] = sample

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
        }

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
            },
            canonicalSyncDao = mock {
                onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                    ops.add(invocation.getArgument(0))
                    Unit
                }
            },
            householdIdentityManager = HouseholdIdentityManager(mock())
        )

        val result = domainService.discardItem("item-disc-1", reason = "Already discarded")

        assertTrue(result is DomainResult.Success)
        assertEquals(0, ops.size)
    }

    @Test
    fun testDiscardItem_itemNotFound_returnsItemNotFound() = runTest {
        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { null }
        }

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
            },
            canonicalSyncDao = mock(),
            householdIdentityManager = HouseholdIdentityManager(mock())
        )

        val result = domainService.discardItem("non-existent-item", reason = "Not found")

        assertTrue(result is DomainResult.Failure)
        assertTrue((result as DomainResult.Failure).error is DomainError.ItemNotFound)
    }

    @Test
    fun testApprovePendingDiscard_setsQuantityZeroAndStatusDiscardedOnMain() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        var seqEntity: CanonicalSyncSequenceEntity? = null
        var approvalStatus = "PENDING_APPROVAL"

        val sample = createSampleItemEntity(id = "item-approval-1", quantity = 1.0, status = ItemStatus.AVAILABLE, revision = 1L)
        itemsMap[sample.id] = sample

        val pendingApproval = SyncPendingApprovalEntity(
            approvalId = "app-123",
            operationId = "op-follower-777",
            deviceId = "follower-dev-1",
            targetItemId = "item-approval-1",
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
            requestedBaseRevision = 1L,
            currentRevisionAtRequest = 1L,
            currentItemSnapshotJson = "{}",
            payloadJson = "{\"reason\":\"Spoiled\"}",
            status = "PENDING_APPROVAL",
            createdAt = 1000L
        )

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { updateItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[item.id] = item
                Unit
            }
        }

        val canonicalSyncDao: CanonicalSyncDao = mock {
            onBlocking { getApprovalById("app-123") } doAnswer { pendingApproval.copy(status = approvalStatus) }
            onBlocking { updateApprovalStatus(any(), any()) } doAnswer { invocation ->
                approvalStatus = invocation.getArgument(1)
                Unit
            }
            onBlocking { getSequenceEntity() } doAnswer { seqEntity }
            onBlocking { updateSequenceEntity(any()) } doAnswer { invocation ->
                seqEntity = invocation.getArgument(0)
                Unit
            }
            onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                ops.add(invocation.getArgument(0))
                Unit
            }
        }

        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer {
                HouseholdIdentityEntity("house-1", "Main Household", "main-device-100", System.currentTimeMillis())
            }
        }

        val approvalRepo = SyncConflictApprovalRepository(
            canonicalSyncDao = canonicalSyncDao,
            pairedDeviceDao = mock(),
            inventoryDao = inventoryDao,
            householdIdentityDao = identityDao,
            transactionRunner = TestTransactionRunner()
        )

        val result = approvalRepo.approvePendingDiscard("app-123")

        assertTrue(result is ResolutionResult.Success)

        // 1. Item updated on Main with quantity 0.0, status DISCARDED, revision 2
        val updated = itemsMap["item-approval-1"]
        assertNotNull(updated)
        assertEquals(0.0, updated!!.quantity!!, 0.001)
        assertEquals(ItemStatus.DISCARDED, updated.status)
        assertEquals(2L, updated.revision)

        // 2. Exactly 1 canonical APPROVED_DISCARD operation emitted
        assertEquals(1, ops.size)
        val op = ops[0]
        assertEquals(SyncOperationType.REQUEST_DISCARD_ITEM.name, op.operationType)
        assertEquals("APPROVED_DISCARD", op.resultCode)
        assertEquals("item-approval-1", op.targetItemId)
        assertEquals(1L, op.baseRevision)

        // 3. Approval status updated to APPROVED
        assertEquals("APPROVED", approvalStatus)
    }

    @Test
    fun testRejectPendingDiscard_preservesMainQuantityAndStatus() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        var seqEntity: CanonicalSyncSequenceEntity? = null
        var approvalStatus = "PENDING_APPROVAL"

        val sample = createSampleItemEntity(id = "item-approval-1", quantity = 1.0, status = ItemStatus.AVAILABLE, revision = 1L)
        itemsMap[sample.id] = sample

        val pendingApproval = SyncPendingApprovalEntity(
            approvalId = "app-123",
            operationId = "op-follower-777",
            deviceId = "follower-dev-1",
            targetItemId = "item-approval-1",
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
            requestedBaseRevision = 1L,
            currentRevisionAtRequest = 1L,
            currentItemSnapshotJson = "{}",
            payloadJson = "{\"reason\":\"Spoiled\"}",
            status = "PENDING_APPROVAL",
            createdAt = 1000L
        )

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { updateItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[item.id] = item
                Unit
            }
        }

        val canonicalSyncDao: CanonicalSyncDao = mock {
            onBlocking { getApprovalById("app-123") } doAnswer { pendingApproval.copy(status = approvalStatus) }
            onBlocking { updateApprovalStatus(any(), any()) } doAnswer { invocation ->
                approvalStatus = invocation.getArgument(1)
                Unit
            }
            onBlocking { getSequenceEntity() } doAnswer { seqEntity }
            onBlocking { updateSequenceEntity(any()) } doAnswer { invocation ->
                seqEntity = invocation.getArgument(0)
                Unit
            }
            onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                ops.add(invocation.getArgument(0))
                Unit
            }
        }

        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer {
                HouseholdIdentityEntity("house-1", "Main Household", "main-device-100", System.currentTimeMillis())
            }
        }

        val approvalRepo = SyncConflictApprovalRepository(
            canonicalSyncDao = canonicalSyncDao,
            pairedDeviceDao = mock(),
            inventoryDao = inventoryDao,
            householdIdentityDao = identityDao,
            transactionRunner = TestTransactionRunner()
        )

        val result = approvalRepo.rejectPendingDiscard("app-123")

        assertTrue(result is ResolutionResult.Success)

        // Item quantity and status on Main REMAIN UNCHANGED
        val itemAfterReject = itemsMap["item-approval-1"]
        assertNotNull(itemAfterReject)
        assertEquals(1.0, itemAfterReject!!.quantity!!, 0.001)
        assertEquals(ItemStatus.AVAILABLE, itemAfterReject.status)
        assertEquals(1L, itemAfterReject.revision)

        // Approval status updated to REJECTED
        assertEquals("REJECTED", approvalStatus)
    }

    @Test
    fun testRecordLocalRequestDiscard_duplicatePendingRequest_throwsIllegalStateException() = runTest {
        val followerItemsMap = mutableMapOf<String, FollowerInventoryItemEntity>()
        val followerOutbox = mutableListOf<FollowerSyncOutboxEntity>()

        val sample = FollowerInventoryItemEntity(
            itemId = "item-dup-1",
            barcode = null,
            canonicalName = "butter",
            confirmedDisplayName = "Butter",
            confirmedBrand = null,
            confirmedCategory = "REFRIGERATED",
            confirmedStorageLocation = "FRIDGE",
            confirmedQuantity = 1.0,
            confirmedQuantityUnit = "UNIT",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "Butter",
            brand = null,
            category = "REFRIGERATED",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "UNIT",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = false,
            updatedAt = 1000L
        )
        followerItemsMap[sample.itemId] = sample

        val existingOp = FollowerSyncOutboxEntity(
            operationId = "op-dup-1",
            deviceId = "follower-1",
            deviceSequence = 1L,
            protocolVersion = 1,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
            targetItemId = "item-dup-1",
            baseRevision = 1L,
            payloadJson = "{}",
            createdAt = 1000L,
            status = "PENDING",
            createdLocallyAt = 1000L
        )
        followerOutbox.add(existingOp)

        val followerDao: FollowerDao = mock {
            onBlocking { getItemById("item-dup-1") } doAnswer { followerItemsMap["item-dup-1"] }
            onBlocking { getUnresolvedOutboxOpsForTargetItem("item-dup-1") } doAnswer { followerOutbox.toList() }
        }

        val engine = object : FollowerLocalSyncEngine(
            followerDatabase = object : FollowerDatabase() {
                override fun followerDao(): FollowerDao = followerDao
                override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
                override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
                override fun clearAllTables() {}
                override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
                @Deprecated("Deprecated in Room")
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
            }
        ) {
            override suspend fun <T> runTransaction(block: suspend (FollowerDao) -> T): T {
                return block(followerDao)
            }
        }

        var thrownException: Throwable? = null
        try {
            engine.recordLocalRequestDiscard("follower-1", "item-dup-1", "Duplicate test")
        } catch (e: Throwable) {
            thrownException = e
        }

        assertNotNull(thrownException)
        assertTrue(thrownException is DuplicateDiscardRequestException)
        assertEquals("Discard request for item item-dup-1 is already pending approval", thrownException!!.message)
    }

    @Test
    fun testDiscardItem_onFollower_duplicatePendingRequest_returnsDuplicateOperation() = runTest {
        val followerItemsMap = mutableMapOf<String, FollowerInventoryItemEntity>()
        val followerOutbox = mutableListOf<FollowerSyncOutboxEntity>()

        val sample = FollowerInventoryItemEntity(
            itemId = "item-dup-2",
            barcode = null,
            canonicalName = "milk",
            confirmedDisplayName = "Milk",
            confirmedBrand = null,
            confirmedCategory = "REFRIGERATED",
            confirmedStorageLocation = "FRIDGE",
            confirmedQuantity = 1.0,
            confirmedQuantityUnit = "LITRE",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "Milk",
            brand = null,
            category = "REFRIGERATED",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "LITRE",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = false,
            updatedAt = 1000L
        )
        followerItemsMap[sample.itemId] = sample

        val existingOp = FollowerSyncOutboxEntity(
            operationId = "op-dup-2",
            deviceId = "follower-1",
            deviceSequence = 1L,
            protocolVersion = 1,
            operationType = SyncOperationType.REQUEST_DISCARD_ITEM.name,
            targetItemId = "item-dup-2",
            baseRevision = 1L,
            payloadJson = "{}",
            createdAt = 1000L,
            status = "PENDING",
            createdLocallyAt = 1000L
        )
        followerOutbox.add(existingOp)

        val followerDao: FollowerDao = mock {
            onBlocking { getItemById("item-dup-2") } doAnswer { followerItemsMap["item-dup-2"] }
            onBlocking { getUnresolvedOutboxOpsForTargetItem("item-dup-2") } doAnswer { followerOutbox.toList() }
        }

        val engine = object : FollowerLocalSyncEngine(
            followerDatabase = object : FollowerDatabase() {
                override fun followerDao(): FollowerDao = followerDao
                override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
                override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
                override fun clearAllTables() {}
                override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
                @Deprecated("Deprecated in Room")
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
            }
        ) {
            override suspend fun <T> runTransaction(block: suspend (FollowerDao) -> T): T {
                return block(followerDao)
            }
        }

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = mock(),
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(paired = true)) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.FOLLOWER
                override suspend fun isMainAsync(): Boolean = false
            },
            canonicalSyncDao = mock(),
            householdIdentityManager = HouseholdIdentityManager(mock()),
            followerDatabase = object : FollowerDatabase() {
                override fun followerDao(): FollowerDao = followerDao
                override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
                override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
                override fun clearAllTables() {}
                override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
                @Deprecated("Deprecated in Room")
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
            },
            followerLocalSyncEngine = engine,
            followerIdentityStore = object : FollowerIdentityStore(null) {
                override fun getOrCreateDeviceId(): String = "follower-1"
            }
        )

        val result = domainService.discardItem("item-dup-2", reason = "Second discard attempt")

        assertTrue(result is DomainResult.Failure)
        assertTrue((result as DomainResult.Failure).error is DomainError.DuplicateOperation)
    }

    @Test
    fun testDiscardItem_onFollower_unrelatedIllegalStateException_returnsTransactionFailed() = runTest {
        val followerItemsMap = mutableMapOf<String, FollowerInventoryItemEntity>()

        val sample = FollowerInventoryItemEntity(
            itemId = "item-unrelated-err",
            barcode = null,
            canonicalName = "cheese",
            confirmedDisplayName = "Cheese",
            confirmedBrand = null,
            confirmedCategory = "REFRIGERATED",
            confirmedStorageLocation = "FRIDGE",
            confirmedQuantity = 1.0,
            confirmedQuantityUnit = "UNIT",
            confirmedNotes = null,
            confirmedStatus = "AVAILABLE",
            mainConfirmedRevision = 1L,
            displayName = "Cheese",
            brand = null,
            category = "REFRIGERATED",
            storageLocation = "FRIDGE",
            quantity = 1.0,
            quantityUnit = "UNIT",
            notes = null,
            status = "AVAILABLE",
            optimisticRevision = 1L,
            isPendingSync = false,
            updatedAt = 1000L
        )
        followerItemsMap[sample.itemId] = sample

        val followerDao: FollowerDao = mock {
            onBlocking { getItemById("item-unrelated-err") } doAnswer { followerItemsMap["item-unrelated-err"] }
        }

        val failingEngine = object : FollowerLocalSyncEngine(
            followerDatabase = object : FollowerDatabase() {
                override fun followerDao(): FollowerDao = followerDao
                override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
                override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
                override fun clearAllTables() {}
                override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
                @Deprecated("Deprecated in Room")
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
            }
        ) {
            override suspend fun recordLocalRequestDiscard(
                deviceId: String,
                targetItemId: String,
                reason: String?
            ): FollowerSyncOutboxEntity {
                throw IllegalStateException("Unrelated database state error")
            }
        }

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = mock(),
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(paired = true)) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.FOLLOWER
                override suspend fun isMainAsync(): Boolean = false
            },
            canonicalSyncDao = mock(),
            householdIdentityManager = HouseholdIdentityManager(mock()),
            followerDatabase = object : FollowerDatabase() {
                override fun followerDao(): FollowerDao = followerDao
                override fun mealDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealDao = error("Meal DAO not used by this fake")
                override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
                override fun clearAllTables() {}
                override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
                @Deprecated("Deprecated in Room")
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
            },
            followerLocalSyncEngine = failingEngine,
            followerIdentityStore = object : FollowerIdentityStore(null) {
                override fun getOrCreateDeviceId(): String = "follower-1"
            }
        )

        val result = domainService.discardItem("item-unrelated-err", reason = "Test error")

        assertTrue(result is DomainResult.Failure)
        val error = (result as DomainResult.Failure).error
        assertTrue("Error should be TransactionFailed, but was $error", error is DomainError.TransactionFailed)
    }
}
