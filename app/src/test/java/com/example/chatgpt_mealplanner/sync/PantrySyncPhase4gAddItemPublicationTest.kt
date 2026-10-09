package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.sync.AppRole
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import com.example.chatgpt_mealplanner.sync.contract.AddItemPayloadDto
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

class PantrySyncPhase4gAddItemPublicationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class TestPairingStore : FollowerPairingStore(null) {
        override fun isPaired(): Boolean = false
    }

    @Test
    fun testAddItem_onMain_publishesCanonicalOperationAndReusesIdentity() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        var seqEntity: CanonicalSyncSequenceEntity? = null

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
            onBlocking { insertItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[item.id] = item
                Unit
            }
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
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

        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer {
                HouseholdIdentityEntity("house-1", "Test Household", "main-device-123", System.currentTimeMillis())
            }
        }

        val identityManager = HouseholdIdentityManager(identityDao)

        val appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
            override fun getAppRole(): AppRole = AppRole.MAIN
            override fun isMain(): Boolean = true
            override fun isFollower(): Boolean = false
        }

        val service = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = appRoleResolver,
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = identityManager
        )

        val newItem = InventoryItem(
            id = "item-add-123",
            canonicalName = "fresh apples",
            displayName = "Fresh Apples",
            brand = "Gala",
            category = FoodCategory.OTHER,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 6.0,
            quantityUnit = QuantityUnit.UNIT,
            quantityConfidence = QuantityConfidence.EXACT,
            notes = "Organic apples",
            status = ItemStatus.AVAILABLE,
            revision = 1L
        )

        val result = service.addItem(newItem)
        assertTrue(result is DomainResult.Success)

        // 1. Verify inventory item inserted in Room
        val savedItem = itemsMap["item-add-123"]
        assertNotNull(savedItem)
        assertEquals("Fresh Apples", savedItem!!.displayName)

        // 2. Verify canonical operation published atomically reusing existing main-device-123 identity
        assertEquals(1, ops.size)
        val canonicalOp = ops[0]
        assertEquals(1L, canonicalOp.canonicalSequence)
        assertEquals("main-device-123", canonicalOp.deviceId)
        assertEquals(SyncOperationType.ADD_ITEM.name, canonicalOp.operationType)
        assertEquals("item-add-123", canonicalOp.targetItemId)
        assertEquals("APPLIED", canonicalOp.status)

        // 3. Verify payload JSON structure
        val payload = json.decodeFromString<AddItemPayloadDto>(canonicalOp.payloadJson)
        assertEquals("item-add-123", payload.itemId)
        assertEquals("Fresh Apples", payload.displayName)
        assertEquals("FRIDGE", payload.storageLocation)
        assertEquals(6.0, payload.quantity!!, 0.001)
    }

    @Test
    fun testAddItem_onMain_blankIdentity_failsClosedAndRollsBack() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
            onBlocking { insertItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[item.id] = item
                Unit
            }
        }

        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer {
                HouseholdIdentityEntity("house-1", "Test Household", "", System.currentTimeMillis())
            }
        }

        val appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
            override fun getAppRole(): AppRole = AppRole.MAIN
            override fun isMain(): Boolean = true
            override fun isFollower(): Boolean = false
        }

        val failingTransactionRunner = object : com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T {
                try {
                    return block()
                } catch (e: Exception) {
                    itemsMap.clear()
                    ops.clear()
                    throw e
                }
            }
        }

        val service = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = failingTransactionRunner,
            appRoleResolver = appRoleResolver,
            canonicalSyncDao = mock(),
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val newItem = InventoryItem(
            id = "item-blank-id-123",
            canonicalName = "peaches",
            displayName = "Peaches",
            category = FoodCategory.OTHER,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 2.0,
            quantityUnit = QuantityUnit.UNIT,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            revision = 1L
        )

        var thrown = false
        try {
            service.addItem(newItem)
        } catch (e: IllegalStateException) {
            thrown = true
            assertEquals("Main deviceId is blank", e.message)
        }

        assertTrue("Blank identity on Main must fail closed", thrown)
        assertTrue("Item write must be rolled back", itemsMap.isEmpty())
        assertTrue("No canonical op generated", ops.isEmpty())
    }

    @Test
    fun testAddItem_onFollower_doesNotPublishMainCanonicalOperation() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
            onBlocking { insertItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[item.id] = item
                Unit
            }
        }

        val canonicalSyncDao: CanonicalSyncDao = mock()

        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer {
                HouseholdIdentityEntity("house-1", "Test Household", "main-device-123", System.currentTimeMillis())
            }
        }

        val appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
            override fun getAppRole(): AppRole = AppRole.FOLLOWER
            override fun isMain(): Boolean = false
            override fun isFollower(): Boolean = true
        }

        val service = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = appRoleResolver,
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val newItem = InventoryItem(
            id = "item-follower-123",
            canonicalName = "milk",
            displayName = "Whole Milk",
            category = FoodCategory.OTHER,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = QuantityUnit.LITRE,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            revision = 1L
        )

        val result = service.addItem(newItem)
        assertTrue(result is DomainResult.Success)

        // 1. Inventory item saved
        assertNotNull(itemsMap["item-follower-123"])

        // 2. NO Main canonical operation published for Follower-local write
        assertEquals(0, ops.size)
    }

    @Test
    fun testAddItem_transactionRollback_revertsBothWrites() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
            onBlocking { insertItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[item.id] = item
                Unit
            }
        }

        val canonicalSyncDao: CanonicalSyncDao = mock {
            onBlocking { getSequenceEntity() } doAnswer { null }
            onBlocking { updateSequenceEntity(any()) } doAnswer { Unit }
            onBlocking { insertCanonicalOperation(any()) } doAnswer {
                throw RuntimeException("Database error during canonical operation insert")
            }
        }

        val identityDao: HouseholdIdentityDao = mock {
            onBlocking { getHouseholdIdentity() } doAnswer {
                HouseholdIdentityEntity("house-1", "Test Household", "main-device-123", System.currentTimeMillis())
            }
        }

        val appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
            override fun getAppRole(): AppRole = AppRole.MAIN
            override fun isMain(): Boolean = true
            override fun isFollower(): Boolean = false
        }

        val failingTransactionRunner = object : com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T {
                try {
                    return block()
                } catch (e: Exception) {
                    itemsMap.clear()
                    ops.clear()
                    throw e
                }
            }
        }

        val service = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = failingTransactionRunner,
            appRoleResolver = appRoleResolver,
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val newItem = InventoryItem(
            id = "item-rollback-123",
            canonicalName = "eggs",
            displayName = "Free Range Eggs",
            category = FoodCategory.OTHER,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 12.0,
            quantityUnit = QuantityUnit.UNIT,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            revision = 1L
        )

        var thrown = false
        try {
            service.addItem(newItem)
        } catch (_: Exception) {
            thrown = true
        }

        assertTrue("Transaction failure should throw exception", thrown)
        assertTrue("Item write should be rolled back", itemsMap.isEmpty())
        assertTrue("Canonical op write should be rolled back", ops.isEmpty())
    }

    private class TestTransactionRunner : com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
    }
}
