package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
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
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerIdentityStore
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity
import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.SetQuantityPayloadDto
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

class PantrySyncPhase4gRestockSyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class TestPairingStore(private val paired: Boolean = false) : FollowerPairingStore(null) {
        override fun isPaired(): Boolean = paired
    }

    private fun createSampleItemEntity(
        id: String = "item-restock-1",
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
    fun testRestockItem_onMain_atomicallyUpdatesItemAndPublishesSetQuantity() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        val txs = mutableListOf<InventoryTransactionEntity>()
        var seqEntity: CanonicalSyncSequenceEntity? = null

        val sample = createSampleItemEntity(quantity = 2.0, status = ItemStatus.OUT, revision = 1L)
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

        val result = domainService.restockItem("item-restock-1", addedQuantity = 3.0)

        assertTrue(result is DomainResult.Success)

        // 1. Item updated on Main with new quantity 5.0, status AVAILABLE, revision 2
        val saved = itemsMap["item-restock-1"]
        assertNotNull(saved)
        assertEquals(5.0, saved!!.quantity!!, 0.001)
        assertEquals(ItemStatus.AVAILABLE, saved.status)
        assertEquals(2L, saved.revision)

        // 2. Exactly 1 canonical SET_QUANTITY operation emitted
        assertEquals(1, ops.size)
        val op = ops[0]
        assertEquals(SyncOperationType.SET_QUANTITY.name, op.operationType)
        assertEquals("item-restock-1", op.targetItemId)
        assertEquals(1L, op.baseRevision)
        assertEquals(1L, op.canonicalSequence)

        val payload = json.decodeFromString<SetQuantityPayloadDto>(op.payloadJson)
        assertEquals(5.0, payload.absoluteQuantity, 0.001)

        // 3. Inventory transaction recorded
        assertEquals(1, txs.size)
        assertEquals(TransactionType.RESTOCK, txs[0].type)
        assertEquals(3.0, txs[0].quantityChange!!, 0.001)
    }

    @Test
    fun testRestockItem_onFollower_queuesSetQuantityOutboxAndNoMainCanonicalOperation() = runTest {
        val followerItemsMap = mutableMapOf<String, FollowerInventoryItemEntity>()
        val followerOutbox = mutableListOf<FollowerSyncOutboxEntity>()
        val mainOps = mutableListOf<CanonicalSyncOperationEntity>()

        val sampleFollowerItem = FollowerInventoryItemEntity(
            itemId = "item-restock-1",
            barcode = null,
            canonicalName = "olive oil",
            confirmedDisplayName = "Olive Oil",
            confirmedBrand = "Filippo Berio",
            confirmedCategory = "CUPBOARD",
            confirmedStorageLocation = "CUPBOARD",
            confirmedQuantity = 2.0,
            confirmedQuantityUnit = "LITRE",
            confirmedNotes = null,
            confirmedStatus = "OUT",
            mainConfirmedRevision = 1L,
            displayName = "Olive Oil",
            brand = "Filippo Berio",
            category = "CUPBOARD",
            storageLocation = "CUPBOARD",
            quantity = 2.0,
            quantityUnit = "LITRE",
            notes = null,
            status = "OUT",
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
            override suspend fun recordLocalRestock(
                deviceId: String,
                targetItemId: String,
                addedQuantity: Double
            ): FollowerSyncOutboxEntity {
                val item = followerItemsMap[targetItemId]
                    ?: throw IllegalArgumentException("Target item $targetItemId not found in local projection")

                val newQty = (item.quantity ?: 0.0) + addedQuantity
                val outboxEntity = FollowerSyncOutboxEntity(
                    operationId = "op-restock-777",
                    deviceId = deviceId,
                    deviceSequence = 1L,
                    protocolVersion = 1,
                    operationType = SyncOperationType.SET_QUANTITY.name,
                    targetItemId = targetItemId,
                    baseRevision = 1L,
                    payloadJson = json.encodeToString(SetQuantityPayloadDto(absoluteQuantity = newQty)),
                    createdAt = 1000L,
                    status = "PENDING",
                    createdLocallyAt = 1000L
                )
                followerOutbox.add(outboxEntity)
                followerItemsMap[targetItemId] = item.copy(quantity = newQty, status = "AVAILABLE", isPendingSync = true)
                return outboxEntity
            }
        }

        val identityStore = object : FollowerIdentityStore(null) {
            override fun getOrCreateDeviceId(): String = "follower-device-999"
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
                override fun clearAllTables() {}
                override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
            },
            followerLocalSyncEngine = localSyncEngine,
            followerIdentityStore = identityStore
        )

        val result = domainService.restockItem("item-restock-1", addedQuantity = 3.0)

        assertTrue(result is DomainResult.Success)

        // 1. Zero Main canonical ops emitted
        assertEquals(0, mainOps.size)

        // 2. Exactly 1 Follower outbox SET_QUANTITY op created
        assertEquals(1, followerOutbox.size)
        val ob = followerOutbox[0]
        assertEquals(SyncOperationType.SET_QUANTITY.name, ob.operationType)
        assertEquals("item-restock-1", ob.targetItemId)
        assertEquals(1L, ob.baseRevision)
        assertEquals("PENDING", ob.status)

        val payload = json.decodeFromString<SetQuantityPayloadDto>(ob.payloadJson)
        assertEquals(5.0, payload.absoluteQuantity, 0.001)

        // 3. Optimistic working quantity on Follower becomes 5.0, status AVAILABLE, isPendingSync = true
        val updated = followerItemsMap["item-restock-1"]
        assertNotNull(updated)
        assertEquals(5.0, updated!!.quantity!!, 0.001)
        assertEquals("AVAILABLE", updated.status)
        assertEquals(true, updated.isPendingSync)
    }

    @Test
    fun testRestockItem_onFollower_pendingOperationExists_failsClosedWithDuplicateOperation() = runTest {
        val followerItemsMap = mutableMapOf<String, FollowerInventoryItemEntity>()
        val pendingOutboxList = listOf(
            FollowerSyncOutboxEntity(
                operationId = "pending-op-1",
                deviceId = "dev-1",
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = "item-restock-1",
                baseRevision = 1L,
                payloadJson = "{}",
                createdAt = 1000L,
                status = "PENDING",
                createdLocallyAt = 1000L
            )
        )

        val sampleFollowerItem = FollowerInventoryItemEntity(
            itemId = "item-restock-1",
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
            isPendingSync = true,
            updatedAt = 1000L
        )
        followerItemsMap[sampleFollowerItem.itemId] = sampleFollowerItem

        val followerDao: FollowerDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                followerItemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getUnresolvedOutboxOpsForTargetItem(any()) } doAnswer { pendingOutboxList }
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
                override fun clearAllTables() {}
                override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
            },
            followerLocalSyncEngine = object : FollowerLocalSyncEngine(null) {},
            followerIdentityStore = object : FollowerIdentityStore(null) {
                override fun getOrCreateDeviceId(): String = "follower-device-999"
            }
        )

        val result = domainService.restockItem("item-restock-1", addedQuantity = 2.0)

        assertTrue(result is DomainResult.Failure)
        assertTrue((result as DomainResult.Failure).error is DomainError.DuplicateOperation)
    }

    @Test
    fun testRestockItem_invalidInputs_returnsInvalidQuantity() = runTest {
        val domainService = InventoryDomainServiceImpl(
            inventoryDao = mock(),
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

        val resultNull = domainService.restockItem("item-restock-1", addedQuantity = null)
        assertTrue(resultNull is DomainResult.Failure)
        assertTrue((resultNull as DomainResult.Failure).error is DomainError.InvalidQuantity)

        val resultZero = domainService.restockItem("item-restock-1", addedQuantity = 0.0)
        assertTrue(resultZero is DomainResult.Failure)
        assertTrue((resultZero as DomainResult.Failure).error is DomainError.InvalidQuantity)

        val resultNegative = domainService.restockItem("item-restock-1", addedQuantity = -2.0)
        assertTrue(resultNegative is DomainResult.Failure)
        assertTrue((resultNegative as DomainResult.Failure).error is DomainError.InvalidQuantity)

        val resultNaN = domainService.restockItem("item-restock-1", addedQuantity = Double.NaN)
        assertTrue(resultNaN is DomainResult.Failure)
        assertTrue((resultNaN as DomainResult.Failure).error is DomainError.InvalidQuantity)
    }

    @Test
    fun testRestockItem_itemNotFound_returnsItemNotFound() = runTest {
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

        val result = domainService.restockItem("non-existent-item", addedQuantity = 2.0)

        assertTrue(result is DomainResult.Failure)
        assertTrue((result as DomainResult.Failure).error is DomainError.ItemNotFound)
    }
}
