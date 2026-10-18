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

class PantrySyncPhase4gQuantityCorrectionPublicationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class TestPairingStore(private val paired: Boolean = false) : FollowerPairingStore(null) {
        override fun isPaired(): Boolean = paired
    }

    private fun createSampleItemEntity(
        id: String = "item-qty-1",
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
    fun testRecordUserCorrection_onMain_positiveQuantity_publishesSetQuantityOperation() = runTest {
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

        val result = domainService.recordUserCorrection(
            itemId = "item-qty-1",
            newQuantity = 5.0,
            newUnit = QuantityUnit.LITRE,
            newConfidence = QuantityConfidence.EXACT,
            newStatus = null,
            note = "Restocked 5L"
        )

        assertTrue(result is DomainResult.Success)

        // 1. Verify item updated in Room with revision 2 and quantity 5.0
        val saved = itemsMap["item-qty-1"]
        assertNotNull(saved)
        assertEquals(5.0, saved!!.quantity!!, 0.001)
        assertEquals(2L, saved.revision)
        assertEquals(ItemStatus.AVAILABLE, saved.status)

        // 2. Verify canonical SET_QUANTITY op emitted
        assertEquals(1, ops.size)
        val op = ops[0]
        assertEquals(SyncOperationType.SET_QUANTITY.name, op.operationType)
        assertEquals("item-qty-1", op.targetItemId)
        assertEquals(1L, op.baseRevision)
        assertEquals(1L, op.canonicalSequence)

        val payload = json.decodeFromString<SetQuantityPayloadDto>(op.payloadJson)
        assertEquals(5.0, payload.absoluteQuantity, 0.001)

        // 3. Verify transaction history recorded
        assertEquals(1, txs.size)
        assertEquals(TransactionType.USER_CORRECTION, txs[0].type)
        assertEquals(5.0, txs[0].quantityChange!!, 0.001)
    }

    @Test
    fun testRecordUserCorrection_zeroQuantity_publishesSetQuantityZeroAndSetsStatusOut() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        val txs = mutableListOf<InventoryTransactionEntity>()

        val sample = createSampleItemEntity(quantity = 2.0, status = ItemStatus.AVAILABLE, revision = 1L)
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
            onBlocking { getSequenceEntity() } doAnswer { null }
            onBlocking { updateSequenceEntity(any()) } doAnswer { Unit }
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

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock {
                onBlocking { insertTransaction(any()) } doAnswer { invocation ->
                    txs.add(invocation.getArgument(0))
                    Unit
                }
            },
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(), identityDao) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
                override suspend fun isMainAsync(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val result = domainService.markAsOut("item-qty-1")
        assertTrue(result is DomainResult.Success)

        // 1. Verify item updated to quantity 0.0 and status OUT
        val saved = itemsMap["item-qty-1"]
        assertNotNull(saved)
        assertEquals(0.0, saved!!.quantity!!, 0.001)
        assertEquals(ItemStatus.OUT, saved.status)
        assertEquals(2L, saved.revision)

        // 2. Verify SET_QUANTITY op published with absoluteQuantity = 0.0
        assertEquals(1, ops.size)
        assertEquals(SyncOperationType.SET_QUANTITY.name, ops[0].operationType)
        val payload = json.decodeFromString<SetQuantityPayloadDto>(ops[0].payloadJson)
        assertEquals(0.0, payload.absoluteQuantity, 0.001)
    }

    @Test
    fun testRecordUserCorrection_positiveQuantityWhenOut_updatesStatusToAvailable() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val sample = createSampleItemEntity(quantity = 0.0, status = ItemStatus.OUT, revision = 2L)
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
            onBlocking { getSequenceEntity() } doAnswer { null }
            onBlocking { updateSequenceEntity(any()) } doAnswer { Unit }
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

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(), identityDao) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
                override suspend fun isMainAsync(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val result = domainService.recordUserCorrection(
            itemId = "item-qty-1",
            newQuantity = 3.0,
            newUnit = QuantityUnit.LITRE,
            newConfidence = QuantityConfidence.EXACT,
            newStatus = null,
            note = "Restocked"
        )

        assertTrue(result is DomainResult.Success)

        val saved = itemsMap["item-qty-1"]
        assertNotNull(saved)
        assertEquals(3.0, saved!!.quantity!!, 0.001)
        assertEquals(ItemStatus.AVAILABLE, saved.status)
        assertEquals(3L, saved.revision)

        assertEquals(1, ops.size)
        val payload = json.decodeFromString<SetQuantityPayloadDto>(ops[0].payloadJson)
        assertEquals(3.0, payload.absoluteQuantity, 0.001)
    }

    @Test
    fun testRecordUserCorrection_onFollower_queuesFollowerOutboxAndNoMainCanonicalOperation() = runTest {
        val followerItemsMap = mutableMapOf<String, com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>()
        val followerOutbox = mutableListOf<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity>()
        val mainOps = mutableListOf<CanonicalSyncOperationEntity>()

        val sampleFollowerItem = com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
            itemId = "item-qty-1",
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

        val followerDao: com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                followerItemsMap[invocation.getArgument<String>(0)]
            }
        }

        val localSyncEngine = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(null) {
            override suspend fun recordLocalSetQuantity(
                deviceId: String,
                targetItemId: String,
                absoluteQuantity: Double
            ): com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity {
                val outboxEntity = com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity(
                    operationId = "op-set-qty-123",
                    deviceId = deviceId,
                    deviceSequence = 1L,
                    protocolVersion = 1,
                    operationType = SyncOperationType.SET_QUANTITY.name,
                    targetItemId = targetItemId,
                    baseRevision = 1L,
                    payloadJson = json.encodeToString(SetQuantityPayloadDto(absoluteQuantity = absoluteQuantity)),
                    createdAt = 1000L,
                    status = "PENDING",
                    createdLocallyAt = 1000L
                )
                followerOutbox.add(outboxEntity)
                val item = followerItemsMap[targetItemId]
                if (item != null) {
                    followerItemsMap[targetItemId] = item.copy(quantity = absoluteQuantity, isPendingSync = true)
                }
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
            followerDatabase = null,
            followerLocalSyncEngine = localSyncEngine,
            followerIdentityStore = identityStore
        )

        val result = domainService.recordUserCorrection(
            itemId = "item-qty-1",
            newQuantity = 4.0,
            newUnit = QuantityUnit.LITRE,
            newConfidence = QuantityConfidence.EXACT,
            newStatus = null,
            note = "Follower correction"
        )

        assertTrue(result is DomainResult.Success)

        // 1. ZERO Main canonical ops published
        assertEquals(0, mainOps.size)

        // 2. Exactly ONE Follower outbox SET_QUANTITY operation created
        assertEquals(1, followerOutbox.size)
        val ob = followerOutbox[0]
        assertEquals(SyncOperationType.SET_QUANTITY.name, ob.operationType)
        assertEquals("follower-device-999", ob.deviceId)
        assertEquals("item-qty-1", ob.targetItemId)
        assertEquals(1L, ob.baseRevision)
        assertEquals("PENDING", ob.status)

        val payload = json.decodeFromString<SetQuantityPayloadDto>(ob.payloadJson)
        assertEquals(4.0, payload.absoluteQuantity, 0.001)

        // 3. Optimistic working projection updated on Follower
        val updated = followerItemsMap["item-qty-1"]
        assertNotNull(updated)
        assertEquals(4.0, updated!!.quantity!!, 0.001)
        assertEquals(true, updated.isPendingSync)
    }

    @Test
    fun testRecordUserCorrection_nullQuantity_doesNotPublishSetQuantityOperation() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

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

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(), identityDao) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
                override suspend fun isMainAsync(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val result = domainService.recordUserCorrection(
            itemId = "item-qty-1",
            newQuantity = null,
            newUnit = null,
            newConfidence = QuantityConfidence.UNKNOWN,
            newStatus = null,
            note = "Cleared quantity"
        )

        assertTrue(result is DomainResult.Success)

        // Item quantity set to null
        val saved = itemsMap["item-qty-1"]
        assertNotNull(saved)
        assertEquals(null, saved!!.quantity)

        // ZERO SET_QUANTITY ops published because newQuantity was null
        assertEquals(0, ops.size)
    }

    @Test
    fun testRecordUserCorrection_itemNotFound_returnsFailure() = runTest {
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

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
                override suspend fun isMainAsync(): Boolean = true
            },
            canonicalSyncDao = mock {
                onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                    ops.add(invocation.getArgument(0))
                    Unit
                }
            },
            householdIdentityManager = HouseholdIdentityManager(mock())
        )

        val result = domainService.recordUserCorrection(
            itemId = "non-existent-item",
            newQuantity = 5.0,
            newUnit = QuantityUnit.UNIT,
            newConfidence = QuantityConfidence.EXACT,
            newStatus = null,
            note = "Not found"
        )

        assertTrue(result is DomainResult.Failure)
        assertTrue((result as DomainResult.Failure).error is DomainError.ItemNotFound)
        assertEquals(0, ops.size)
    }

    @Test
    fun testRecordUserCorrection_onFollower_missingDependencies_returnsTransactionFailed() = runTest {
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
            followerDatabase = null,
            followerLocalSyncEngine = null,
            followerIdentityStore = null
        )

        val result = domainService.recordUserCorrection(
            itemId = "item-qty-1",
            newQuantity = 5.0,
            newUnit = QuantityUnit.UNIT,
            newConfidence = QuantityConfidence.EXACT,
            newStatus = null,
            note = "Follower edit without engine"
        )

        assertTrue(result is DomainResult.Failure)
        assertTrue((result as DomainResult.Failure).error is DomainError.TransactionFailed)
    }

    @Test
    fun testRecordUserCorrection_onFollower_invalidQuantity_returnsInvalidQuantity() = runTest {
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
            followerDatabase = null,
            followerLocalSyncEngine = null,
            followerIdentityStore = null
        )

        val resultNull = domainService.recordUserCorrection(
            itemId = "item-qty-1",
            newQuantity = null,
            newUnit = null,
            newConfidence = QuantityConfidence.UNKNOWN,
            newStatus = null,
            note = "Null quantity"
        )

        assertTrue(resultNull is DomainResult.Failure)
        assertTrue((resultNull as DomainResult.Failure).error is DomainError.InvalidQuantity)

        val resultNegative = domainService.recordUserCorrection(
            itemId = "item-qty-1",
            newQuantity = -3.0,
            newUnit = QuantityUnit.UNIT,
            newConfidence = QuantityConfidence.EXACT,
            newStatus = null,
            note = "Negative quantity"
        )

        assertTrue(resultNegative is DomainResult.Failure)
        assertTrue((resultNegative as DomainResult.Failure).error is DomainError.InvalidQuantity)
    }

    @Test
    fun testRecordUserCorrection_onFollower_unsupportedUnitChange_returnsInvalidUnitConversion() = runTest {
        val followerItemsMap = mutableMapOf<String, com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity>()
        val followerOutbox = mutableListOf<com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity>()

        val sampleFollowerItem = com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity(
            itemId = "item-qty-1",
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



        val followerDao: com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                followerItemsMap[invocation.getArgument<String>(0)]
            }
        }

        val followerDb = object : FollowerDatabase() {
            override fun followerDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao = followerDao
            override fun mealRequestDao(): com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerMealRequestDao = error("Meal projection not used in this inventory fixture")
            override fun clearAllTables() {}
            override fun createInvalidationTracker(): androidx.room.InvalidationTracker = mock()
            override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = mock()
        }

        val localSyncEngine = object : com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerLocalSyncEngine(null) {
            override suspend fun recordLocalSetQuantity(
                deviceId: String,
                targetItemId: String,
                absoluteQuantity: Double
            ): com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity {
                val outboxEntity = com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity(
                    operationId = "op-set-qty-999",
                    deviceId = deviceId,
                    deviceSequence = 1L,
                    protocolVersion = 1,
                    operationType = SyncOperationType.SET_QUANTITY.name,
                    targetItemId = targetItemId,
                    baseRevision = 1L,
                    payloadJson = json.encodeToString(SetQuantityPayloadDto(absoluteQuantity = absoluteQuantity)),
                    createdAt = 1000L,
                    status = "PENDING",
                    createdLocallyAt = 1000L
                )
                followerOutbox.add(outboxEntity)
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
            canonicalSyncDao = mock(),
            householdIdentityManager = HouseholdIdentityManager(mock()),
            followerDatabase = followerDb,
            followerLocalSyncEngine = localSyncEngine,
            followerIdentityStore = identityStore
        )

        // Attempt unit change from LITRE to GRAM on Follower
        val resultUnitChange = domainService.recordUserCorrection(
            itemId = "item-qty-1",
            newQuantity = 500.0,
            newUnit = QuantityUnit.GRAM,
            newConfidence = QuantityConfidence.EXACT,
            newStatus = null,
            note = "Unit change"
        )

        assertTrue(resultUnitChange is DomainResult.Failure)
        val err = (resultUnitChange as DomainResult.Failure).error
        assertTrue("Error should be InvalidUnitConversion", err is DomainError.InvalidUnitConversion)
        assertEquals(QuantityUnit.LITRE, (err as DomainError.InvalidUnitConversion).from)
        assertEquals(QuantityUnit.GRAM, err.to)

        // ZERO Follower outbox entries created
        assertEquals(0, followerOutbox.size)
    }
}
