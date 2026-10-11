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
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
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
    fun testRecordUserCorrection_onFollower_doesNotPublishCanonicalOperation() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val sample = createSampleItemEntity(quantity = 1.0, revision = 1L)
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

        val domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(paired = true)) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.FOLLOWER
                override suspend fun isMainAsync(): Boolean = false
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(mock())
        )

        val result = domainService.recordUserCorrection(
            itemId = "item-qty-1",
            newQuantity = 4.0,
            newUnit = QuantityUnit.LITRE,
            newConfidence = QuantityConfidence.EXACT,
            newStatus = null,
            note = "Follower edit"
        )

        assertTrue(result is DomainResult.Success)

        // Item is updated locally on Follower
        val saved = itemsMap["item-qty-1"]
        assertNotNull(saved)
        assertEquals(4.0, saved!!.quantity!!, 0.001)

        // ZERO canonical ops published because role is FOLLOWER
        assertEquals(0, ops.size)
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
}
