package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
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
import com.example.chatgpt_mealplanner.sync.contract.EditItemPayloadDto
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

class PantrySyncPhase4gEditItemPublicationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class TestPairingStore : FollowerPairingStore(null) {
        override fun isPaired(): Boolean = false
    }

    @Test
    fun testUpdateItem_onMain_publishesCanonicalEditOperationAtomically() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        var seqEntity: CanonicalSyncSequenceEntity? = null

        val initialItem = InventoryItemEntity(
            id = "item-edit-123",
            barcode = null,
            canonicalIngredientId = null,
            canonicalName = "cheddar cheese",
            displayName = "Cheddar Cheese",
            brand = "Old Croc",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = QuantityUnit.KILOGRAM,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            opened = null,
            frozen = false,
            staple = false,
            purchaseDate = null,
            openedDate = null,
            useByDate = null,
            bestBeforeDate = null,
            notes = "Original notes",
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1L
        )
        itemsMap[initialItem.id] = initialItem

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
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

        val editRequest = InventoryItem(
            id = "item-edit-123",
            canonicalName = "cheddar cheese",
            displayName = "Sharp Cheddar Cheese",
            brand = "Cathedral City",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = QuantityUnit.KILOGRAM,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            notes = "Updated sharp notes",
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1L
        )

        val result = service.updateItem(editRequest)
        assertTrue(result is DomainResult.Success)

        // 1. Verify item updated in Room with revision bumped to 2
        val savedItem = itemsMap["item-edit-123"]
        assertNotNull(savedItem)
        assertEquals("Sharp Cheddar Cheese", savedItem!!.displayName)
        assertEquals("Cathedral City", savedItem.brand)
        assertEquals("Updated sharp notes", savedItem.notes)
        assertEquals(2L, savedItem.revision)

        // 2. Verify canonical operation published atomically
        assertEquals(1, ops.size)
        val canonicalOp = ops[0]
        assertEquals(1L, canonicalOp.canonicalSequence)
        assertEquals("main-device-123", canonicalOp.deviceId)
        assertEquals(SyncOperationType.EDIT_ITEM.name, canonicalOp.operationType)
        assertEquals("item-edit-123", canonicalOp.targetItemId)
        assertEquals(1L, canonicalOp.baseRevision)
        assertEquals("APPLIED", canonicalOp.status)

        // 3. Verify payload DTO content
        val payload = json.decodeFromString<EditItemPayloadDto>(canonicalOp.payloadJson)
        assertEquals("Sharp Cheddar Cheese", payload.displayName)
        assertEquals("Cathedral City", payload.brand)
        assertEquals("Updated sharp notes", payload.notes)
        assertEquals("FRIDGE", payload.category)
        assertEquals("FRIDGE", payload.storageLocation)
    }

    @Test
    fun testUpdateItem_statusOnlyChange_isNotNoOp_publishesCanonicalOperationAndIncrementsRevision() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val initialItem = InventoryItemEntity(
            id = "item-status-123",
            barcode = null,
            canonicalIngredientId = null,
            canonicalName = "milk",
            displayName = "Whole Milk",
            brand = "Dairy",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = QuantityUnit.LITRE,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
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
            revision = 1L
        )
        itemsMap[initialItem.id] = initialItem

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
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
                HouseholdIdentityEntity("house-1", "Test Household", "main-device-123", System.currentTimeMillis())
            }
        }

        val service = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
                override fun getAppRole(): AppRole = AppRole.MAIN
                override fun isMain(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        // Status-only change: AVAILABLE -> LOW
        val statusChangeRequest = initialItem.toDomainModel().copy(status = ItemStatus.LOW)

        val result = service.updateItem(statusChangeRequest)
        assertTrue(result is DomainResult.Success)

        // 1. Verify status updated and revision incremented to 2
        val savedItem = itemsMap["item-status-123"]
        assertNotNull(savedItem)
        assertEquals(ItemStatus.LOW, savedItem!!.status)
        assertEquals(2L, savedItem.revision)

        // 2. Verify canonical operation generated
        assertEquals(1, ops.size)
        assertEquals("item-status-123", ops[0].targetItemId)
    }

    @Test
    fun testUpdateItem_useByDateOnlyChange_isNotNoOp_publishesCanonicalOperationAndIncrementsRevision() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val initialItem = InventoryItemEntity(
            id = "item-date-123",
            barcode = null,
            canonicalIngredientId = null,
            canonicalName = "chicken",
            displayName = "Chicken Breast",
            brand = null,
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 500.0,
            quantityUnit = QuantityUnit.GRAM,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
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
            revision = 1L
        )
        itemsMap[initialItem.id] = initialItem

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
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
                HouseholdIdentityEntity("house-1", "Test Household", "main-device-123", System.currentTimeMillis())
            }
        }

        val service = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
                override fun getAppRole(): AppRole = AppRole.MAIN
                override fun isMain(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        // Date-only change: useByDate -> "2026-10-15"
        val dateChangeRequest = initialItem.toDomainModel().copy(useByDate = "2026-10-15")

        val result = service.updateItem(dateChangeRequest)
        assertTrue(result is DomainResult.Success)

        // 1. Verify date updated and revision incremented to 2
        val savedItem = itemsMap["item-date-123"]
        assertNotNull(savedItem)
        assertEquals("2026-10-15", savedItem!!.useByDate)
        assertEquals(2L, savedItem.revision)

        // 2. Verify canonical operation generated
        assertEquals(1, ops.size)
    }

    @Test
    fun testUpdateItem_openedOnlyChange_isNotNoOp_publishesCanonicalOperationAndIncrementsRevision() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val initialItem = InventoryItemEntity(
            id = "item-opened-123",
            barcode = null,
            canonicalIngredientId = null,
            canonicalName = "juice",
            displayName = "Orange Juice",
            brand = "Tropicana",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = QuantityUnit.LITRE,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            opened = false,
            frozen = false,
            staple = false,
            purchaseDate = null,
            openedDate = null,
            useByDate = null,
            bestBeforeDate = null,
            notes = null,
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1L
        )
        itemsMap[initialItem.id] = initialItem

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
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
                HouseholdIdentityEntity("house-1", "Test Household", "main-device-123", System.currentTimeMillis())
            }
        }

        val service = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
                override fun getAppRole(): AppRole = AppRole.MAIN
                override fun isMain(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        // Opened-only change: opened -> true
        val openedChangeRequest = initialItem.toDomainModel().copy(opened = true)

        val result = service.updateItem(openedChangeRequest)
        assertTrue(result is DomainResult.Success)

        // 1. Verify opened updated to true and revision incremented
        val savedItem = itemsMap["item-opened-123"]
        assertNotNull(savedItem)
        assertEquals(true, savedItem!!.opened)
        assertEquals(2L, savedItem.revision)

        // 2. Verify canonical operation generated
        assertEquals(1, ops.size)
    }

    @Test
    fun testUpdateItem_barcodeNormalization_withoutFieldChanges_isNoOp() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val initialItem = InventoryItemEntity(
            id = "item-barcode-noop",
            barcode = "123456789",
            canonicalIngredientId = null,
            canonicalName = "cereal",
            displayName = "Corn Flakes",
            brand = "Kellogg's",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.PACK,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
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
            revision = 2L
        )
        itemsMap[initialItem.id] = initialItem

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
        }

        val canonicalSyncDao: CanonicalSyncDao = mock {
            onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                ops.add(invocation.getArgument(0))
                Unit
            }
        }

        val service = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
                override fun getAppRole(): AppRole = AppRole.MAIN
                override fun isMain(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(mock {
                onBlocking { getHouseholdIdentity() } doAnswer {
                    HouseholdIdentityEntity("house-1", "Test Household", "main-device-123", System.currentTimeMillis())
                }
            })
        )

        // Submitting barcode with whitespace " 123456789 " which normalizes to "123456789"
        val barcodeNoOpRequest = initialItem.toDomainModel().copy(barcode = " 123456789 ")

        val result = service.updateItem(barcodeNoOpRequest)
        assertTrue(result is DomainResult.Success)

        // 1. Revision remains unchanged
        assertEquals(2L, itemsMap["item-barcode-noop"]!!.revision)

        // 2. No operation generated
        assertTrue(ops.isEmpty())
    }

    @Test
    fun testUpdateItem_onMain_noOp_doesNotPublishCanonicalOperationOrBumpRevision() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val initialItem = InventoryItemEntity(
            id = "item-noop-123",
            barcode = null,
            canonicalIngredientId = null,
            canonicalName = "butter",
            displayName = "Salted Butter",
            brand = "Kerrygold",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 2.0,
            quantityUnit = QuantityUnit.PACK,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            opened = null,
            frozen = false,
            staple = false,
            purchaseDate = null,
            openedDate = null,
            useByDate = null,
            bestBeforeDate = null,
            notes = "Keep cold",
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 3L
        )
        itemsMap[initialItem.id] = initialItem

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
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
                HouseholdIdentityEntity("house-1", "Test Household", "main-device-123", System.currentTimeMillis())
            }
        }

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
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        // Submitting identical fields
        val noOpRequest = initialItem.toDomainModel()

        val result = service.updateItem(noOpRequest)
        assertTrue(result is DomainResult.Success)

        // 1. Revision remains unchanged at 3
        assertEquals(3L, itemsMap["item-noop-123"]!!.revision)

        // 2. No canonical operation generated for no-op
        assertTrue(ops.isEmpty())
    }

    @Test
    fun testUpdateItem_onMain_blankIdentity_failsClosedAndRollsBack() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val initialItem = InventoryItemEntity(
            id = "item-blank-id",
            barcode = null,
            canonicalIngredientId = null,
            canonicalName = "jam",
            displayName = "Strawberry Jam",
            brand = "Bonne Maman",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
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
            revision = 1L
        )
        itemsMap[initialItem.id] = initialItem

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
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
                    itemsMap["item-blank-id"] = initialItem
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

        val editRequest = InventoryItem(
            id = "item-blank-id",
            canonicalName = "jam",
            displayName = "Raspberry Jam",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1L
        )

        var thrown = false
        try {
            service.updateItem(editRequest)
        } catch (e: IllegalStateException) {
            thrown = true
            assertEquals("Main deviceId is blank", e.message)
        }

        assertTrue("Blank main identity must fail closed", thrown)
        assertEquals("Revision must roll back to original 1", 1L, itemsMap["item-blank-id"]!!.revision)
        assertEquals("Display name must roll back", "Strawberry Jam", itemsMap["item-blank-id"]!!.displayName)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun testUpdateItem_onFollower_doesNotPublishMainCanonicalOperation() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()

        val initialItem = InventoryItemEntity(
            id = "item-follower-edit",
            barcode = null,
            canonicalIngredientId = null,
            canonicalName = "yogurt",
            displayName = "Greek Yogurt",
            brand = "Fage",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
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
            revision = 2L
        )
        itemsMap[initialItem.id] = initialItem

        val inventoryDao: com.example.chatgpt_mealplanner.core.database.dao.InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { updateItem(any()) } doAnswer { invocation ->
                val item = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[item.id] = item
                Unit
            }
        }

        val canonicalSyncDao: CanonicalSyncDao = mock()

        val appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
            override suspend fun getAppRoleAsync(): AppRole = AppRole.FOLLOWER
            override fun getAppRole(): AppRole = AppRole.FOLLOWER
            override fun isMain(): Boolean = false
            override fun isFollower(): Boolean = true
            override suspend fun isMainAsync(): Boolean = false
        }

        val service = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = mock(),
            transactionDao = mock(),
            stapleRuleDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = appRoleResolver,
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(mock())
        )

        val editRequest = InventoryItem(
            id = "item-follower-edit",
            canonicalName = "yogurt",
            displayName = "Greek Yogurt 0%",
            brand = "Fage",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 2L
        )

        val result = service.updateItem(editRequest)
        assertTrue(result is DomainResult.Success)

        // Item updated locally
        assertEquals("Greek Yogurt 0%", itemsMap["item-follower-edit"]!!.displayName)

        // NO Main canonical operation published for Follower-local edit
        org.mockito.kotlin.verify(canonicalSyncDao, org.mockito.kotlin.never()).insertCanonicalOperation(any())
    }

    private class TestTransactionRunner : com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
    }
}
