package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity
import com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity
import com.example.chatgpt_mealplanner.core.database.dao.MealWithIngredientEntities
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryTransactionEntity
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.MealDomainServiceImpl
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

class PantrySyncPhase4gMealDeductionPublicationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class TestPairingStore(private val paired: Boolean = false) : FollowerPairingStore(null) {
        override fun isPaired(): Boolean = paired
    }

    private fun createSampleItem(
        id: String,
        canonicalIngredientId: String,
        displayName: String,
        quantity: Double = 2.0,
        status: ItemStatus = ItemStatus.AVAILABLE,
        revision: Long = 1L
    ): InventoryItemEntity = InventoryItemEntity(
        id = id,
        barcode = null,
        canonicalIngredientId = canonicalIngredientId,
        canonicalName = displayName.lowercase(),
        displayName = displayName,
        brand = null,
        category = FoodCategory.CUPBOARD,
        storageLocation = StorageLocation.CUPBOARD,
        quantity = quantity,
        quantityUnit = QuantityUnit.LITRE,
        packCount = null,
        packSize = null,
        packSizeUnit = null,
        quantityConfidence = QuantityConfidence.EXACT,
        status = status,
        opened = true,
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
    fun testMarkMealCooked_onMain_singleLotDeduction_publishesSetQuantity() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        val txs = mutableListOf<InventoryTransactionEntity>()
        var seqEntity: CanonicalSyncSequenceEntity? = null

        val lot = createSampleItem("lot-1", "ing-oil", "Olive Oil", quantity = 2.0, revision = 1L)
        itemsMap[lot.id] = lot

        val meal = Meal(
            id = "meal-1",
            title = "Pasta Primavera",
            date = "2026-10-12",
            servings = 2,
            status = MealStatus.PLANNED,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        val ingredient = MealIngredient(
            id = "meal-ing-1",
            mealId = "meal-1",
            canonicalIngredientId = "ing-oil",
            displayName = "Olive Oil",
            plannedQuantity = 1.0,
            plannedUnit = QuantityUnit.LITRE,
            actualQuantity = 1.0,
            actualUnit = QuantityUnit.LITRE
        )

        val mealWithIngredients = MealWithIngredientEntities(
            meal = meal.toEntity(),
            ingredients = listOf(ingredient.toEntity())
        )

        val mealDao: MealDao = mock {
            onBlocking { getMealWithIngredients("meal-1") } doAnswer { mealWithIngredients }
            onBlocking { insertMeal(any()) } doAnswer { Unit }
        }

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getUsableItemsByCanonicalIngredientId("ing-oil") } doAnswer {
                itemsMap.values.filter { it.canonicalIngredientId == "ing-oil" }
            }
            onBlocking { updateItem(any()) } doAnswer { invocation ->
                val updated = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[updated.id] = updated
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
                HouseholdIdentityEntity("house-1", "Main Household", "main-device-100", System.currentTimeMillis())
            }
        }

        val appRoleResolver = object : AppRoleResolver(TestPairingStore(), identityDao) {
            override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
            override suspend fun isMainAsync(): Boolean = true
        }

        val transactionDao: TransactionDao = mock {
            onBlocking { hasCookedTransactionForMeal(any()) } doAnswer { false }
            onBlocking { insertTransaction(any()) } doAnswer { invocation ->
                txs.add(invocation.getArgument(0))
                Unit
            }
        }

        val mealService = MealDomainServiceImpl(
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            transactionDao = transactionDao,
            leftoverDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = appRoleResolver,
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val result = mealService.markMealCooked("meal-1")

        assertTrue(result is DomainResult.Success)

        // 1. Lot quantity deducted on Main from 2.0 -> 1.0, revision 2
        val updatedLot = itemsMap["lot-1"]
        assertNotNull(updatedLot)
        assertEquals(1.0, updatedLot!!.quantity!!, 0.001)
        assertEquals(2L, updatedLot.revision)

        // 2. Exactly 1 canonical SET_QUANTITY operation emitted
        assertEquals(1, ops.size)
        val op = ops[0]
        assertEquals(SyncOperationType.SET_QUANTITY.name, op.operationType)
        assertEquals("lot-1", op.targetItemId)
        assertEquals(1L, op.baseRevision)
        assertEquals(1L, op.canonicalSequence)

        val payload = json.decodeFromString<SetQuantityPayloadDto>(op.payloadJson)
        assertEquals(1.0, payload.absoluteQuantity, 0.001)
    }

    @Test
    fun testMarkMealCooked_insufficientQuantity_publishesNoCanonicalOperations() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        // Lot has only 0.5 L available, but meal requires 1.0 L
        val lot = createSampleItem("lot-1", "ing-oil", "Olive Oil", quantity = 0.5, revision = 1L)
        itemsMap[lot.id] = lot

        val meal = Meal(
            id = "meal-1",
            title = "Pasta Primavera",
            date = "2026-10-12",
            servings = 2,
            status = MealStatus.PLANNED,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        val ingredient = MealIngredient(
            id = "meal-ing-1",
            mealId = "meal-1",
            canonicalIngredientId = "ing-oil",
            displayName = "Olive Oil",
            plannedQuantity = 1.0,
            plannedUnit = QuantityUnit.LITRE,
            actualQuantity = 1.0,
            actualUnit = QuantityUnit.LITRE
        )

        val mealWithIngredients = MealWithIngredientEntities(
            meal = meal.toEntity(),
            ingredients = listOf(ingredient.toEntity())
        )

        val mealDao: MealDao = mock {
            onBlocking { getMealWithIngredients("meal-1") } doAnswer { mealWithIngredients }
        }

        val transactionDao: TransactionDao = mock {
            onBlocking { hasCookedTransactionForMeal(any()) } doAnswer { false }
        }

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getUsableItemsByCanonicalIngredientId("ing-oil") } doAnswer {
                itemsMap.values.filter { it.canonicalIngredientId == "ing-oil" }
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

        val mealService = MealDomainServiceImpl(
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            transactionDao = transactionDao,
            leftoverDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(), identityDao) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
                override suspend fun isMainAsync(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val result = mealService.markMealCooked("meal-1")

        assertTrue(result is DomainResult.Failure)
        assertTrue((result as DomainResult.Failure).error is DomainError.InsufficientQuantity)

        // ZERO canonical operations emitted and lot quantity remains 0.5
        assertEquals(0, ops.size)
        assertEquals(0.5, itemsMap["lot-1"]!!.quantity!!, 0.001)
    }

    @Test
    fun testMarkMealCooked_zeroResult_publishesSetQuantityZeroAndSetsStatusOut() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        var seqEntity: CanonicalSyncSequenceEntity? = null

        val lot = createSampleItem("lot-1", "ing-oil", "Olive Oil", quantity = 1.0, revision = 1L)
        itemsMap[lot.id] = lot

        val meal = Meal(
            id = "meal-1",
            title = "Pasta Primavera",
            date = "2026-10-12",
            servings = 2,
            status = MealStatus.PLANNED,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        val ingredient = MealIngredient(
            id = "meal-ing-1",
            mealId = "meal-1",
            canonicalIngredientId = "ing-oil",
            displayName = "Olive Oil",
            plannedQuantity = 1.0,
            plannedUnit = QuantityUnit.LITRE,
            actualQuantity = 1.0,
            actualUnit = QuantityUnit.LITRE
        )

        val mealWithIngredients = MealWithIngredientEntities(
            meal = meal.toEntity(),
            ingredients = listOf(ingredient.toEntity())
        )

        val mealDao: MealDao = mock {
            onBlocking { getMealWithIngredients("meal-1") } doAnswer { mealWithIngredients }
        }

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getUsableItemsByCanonicalIngredientId("ing-oil") } doAnswer {
                itemsMap.values.filter { it.canonicalIngredientId == "ing-oil" }
            }
            onBlocking { updateItem(any()) } doAnswer { invocation ->
                val updated = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[updated.id] = updated
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
                HouseholdIdentityEntity("house-1", "Main Household", "main-device-100", System.currentTimeMillis())
            }
        }

        val mealService = MealDomainServiceImpl(
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            transactionDao = mock { onBlocking { hasCookedTransactionForMeal(any()) } doAnswer { false } },
            leftoverDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(), identityDao) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
                override suspend fun isMainAsync(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val result = mealService.markMealCooked("meal-1")

        assertTrue(result is DomainResult.Success)

        // Quantity 0.0, status OUT
        val updatedLot = itemsMap["lot-1"]
        assertNotNull(updatedLot)
        assertEquals(0.0, updatedLot!!.quantity!!, 0.001)
        assertEquals(ItemStatus.OUT, updatedLot.status)

        // 1 canonical SET_QUANTITY operation emitted with payload 0.0
        assertEquals(1, ops.size)
        val payload = json.decodeFromString<SetQuantityPayloadDto>(ops[0].payloadJson)
        assertEquals(0.0, payload.absoluteQuantity, 0.001)
    }

    @Test
    fun testMarkMealCooked_onFollower_failsClosedWithoutMutatingMainInventory() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val lot = createSampleItem("lot-1", "ing-oil", "Olive Oil", quantity = 2.0, revision = 1L)
        itemsMap[lot.id] = lot

        val meal = Meal(
            id = "meal-1",
            title = "Pasta Primavera",
            date = "2026-10-12",
            servings = 2,
            status = MealStatus.PLANNED,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        val ingredient = MealIngredient(
            id = "meal-ing-1",
            mealId = "meal-1",
            canonicalIngredientId = "ing-oil",
            displayName = "Olive Oil",
            plannedQuantity = 1.0,
            plannedUnit = QuantityUnit.LITRE,
            actualQuantity = 1.0,
            actualUnit = QuantityUnit.LITRE
        )

        val mealWithIngredients = MealWithIngredientEntities(
            meal = meal.toEntity(),
            ingredients = listOf(ingredient.toEntity())
        )

        val mealDao: MealDao = mock {
            onBlocking { getMealWithIngredients("meal-1") } doAnswer { mealWithIngredients }
        }

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getUsableItemsByCanonicalIngredientId("ing-oil") } doAnswer {
                itemsMap.values.filter { it.canonicalIngredientId == "ing-oil" }
            }
        }

        val mealService = MealDomainServiceImpl(
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            transactionDao = mock { onBlocking { hasCookedTransactionForMeal(any()) } doAnswer { false } },
            leftoverDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(paired = true)) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.FOLLOWER
                override suspend fun isMainAsync(): Boolean = false
            },
            canonicalSyncDao = mock {
                onBlocking { insertCanonicalOperation(any()) } doAnswer { invocation ->
                    ops.add(invocation.getArgument(0))
                    Unit
                }
            },
            householdIdentityManager = HouseholdIdentityManager(mock())
        )

        val result = mealService.markMealCooked("meal-1")

        assertTrue(result is DomainResult.Failure)
        assertTrue((result as DomainResult.Failure).error is DomainError.TransactionFailed)
        assertEquals(0, ops.size)
    }

    @Test
    fun testMarkMealCooked_missingDependencies_failsClosed() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()

        val lot = createSampleItem("lot-1", "ing-oil", "Olive Oil", quantity = 2.0, revision = 1L)
        itemsMap[lot.id] = lot

        val meal = Meal(
            id = "meal-1",
            title = "Pasta Primavera",
            date = "2026-10-12",
            servings = 2,
            status = MealStatus.PLANNED,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        val ingredient = MealIngredient(
            id = "meal-ing-1",
            mealId = "meal-1",
            canonicalIngredientId = "ing-oil",
            displayName = "Olive Oil",
            plannedQuantity = 1.0,
            plannedUnit = QuantityUnit.LITRE,
            actualQuantity = 1.0,
            actualUnit = QuantityUnit.LITRE
        )

        val mealWithIngredients = MealWithIngredientEntities(
            meal = meal.toEntity(),
            ingredients = listOf(ingredient.toEntity())
        )

        val mealDao: MealDao = mock {
            onBlocking { getMealWithIngredients("meal-1") } doAnswer { mealWithIngredients }
        }

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getUsableItemsByCanonicalIngredientId("ing-oil") } doAnswer {
                itemsMap.values.filter { it.canonicalIngredientId == "ing-oil" }
            }
        }

        val mealService = MealDomainServiceImpl(
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            transactionDao = mock { onBlocking { hasCookedTransactionForMeal(any()) } doAnswer { false } },
            leftoverDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore()) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
            },
            canonicalSyncDao = null,
            householdIdentityManager = null
        )

        val result = mealService.markMealCooked("meal-1")

        assertTrue(result is DomainResult.Failure)
        assertTrue((result as DomainResult.Failure).error is DomainError.TransactionFailed)
        assertEquals(0, ops.size)
    }

    @Test
    fun testMarkMealCooked_onMain_multiLotDeduction_publishesOneOperationPerLot() = runTest {
        val itemsMap = mutableMapOf<String, InventoryItemEntity>()
        val ops = mutableListOf<CanonicalSyncOperationEntity>()
        var seqCounter = 1L

        val lot1 = createSampleItem("lot-1", "ing-oil", "Olive Oil Lot 1", quantity = 1.0, revision = 1L)
        val lot2 = createSampleItem("lot-2", "ing-oil", "Olive Oil Lot 2", quantity = 2.0, revision = 1L)
        itemsMap[lot1.id] = lot1
        itemsMap[lot2.id] = lot2

        val meal = Meal(
            id = "meal-multi",
            title = "Pasta Party",
            date = "2026-10-12",
            servings = 4,
            status = MealStatus.PLANNED,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        val ingredient = MealIngredient(
            id = "meal-ing-1",
            mealId = "meal-multi",
            canonicalIngredientId = "ing-oil",
            displayName = "Olive Oil",
            plannedQuantity = 2.5,
            plannedUnit = QuantityUnit.LITRE,
            actualQuantity = 2.5,
            actualUnit = QuantityUnit.LITRE
        )

        val mealWithIngredients = MealWithIngredientEntities(
            meal = meal.toEntity(),
            ingredients = listOf(ingredient.toEntity())
        )

        val mealDao: MealDao = mock {
            onBlocking { getMealWithIngredients("meal-multi") } doAnswer { mealWithIngredients }
        }

        val inventoryDao: InventoryDao = mock {
            onBlocking { getItemById(any()) } doAnswer { invocation ->
                itemsMap[invocation.getArgument<String>(0)]
            }
            onBlocking { getUsableItemsByCanonicalIngredientId("ing-oil") } doAnswer {
                itemsMap.values.filter { it.canonicalIngredientId == "ing-oil" }
            }
            onBlocking { updateItem(any()) } doAnswer { invocation ->
                val updated = invocation.getArgument<InventoryItemEntity>(0)
                itemsMap[updated.id] = updated
                Unit
            }
        }

        val canonicalSyncDao: CanonicalSyncDao = mock {
            onBlocking { getSequenceEntity() } doAnswer { CanonicalSyncSequenceEntity(1, seqCounter) }
            onBlocking { updateSequenceEntity(any()) } doAnswer { invocation ->
                seqCounter = invocation.getArgument<CanonicalSyncSequenceEntity>(0).nextSequence
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

        val mealService = MealDomainServiceImpl(
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            transactionDao = mock { onBlocking { hasCookedTransactionForMeal(any()) } doAnswer { false } },
            leftoverDao = mock(),
            transactionRunner = TestTransactionRunner(),
            appRoleResolver = object : AppRoleResolver(TestPairingStore(), identityDao) {
                override suspend fun getAppRoleAsync(): AppRole = AppRole.MAIN
                override suspend fun isMainAsync(): Boolean = true
            },
            canonicalSyncDao = canonicalSyncDao,
            householdIdentityManager = HouseholdIdentityManager(identityDao)
        )

        val result = mealService.markMealCooked("meal-multi")

        assertTrue(result is DomainResult.Success)

        // Lot 1 completely consumed: 1.0 -> 0.0, status OUT, revision 2
        val updatedLot1 = itemsMap["lot-1"]
        assertNotNull(updatedLot1)
        assertEquals(0.0, updatedLot1!!.quantity!!, 0.001)
        assertEquals(ItemStatus.OUT, updatedLot1.status)
        assertEquals(2L, updatedLot1.revision)

        // Lot 2 partially consumed: 2.0 -> 0.5, status AVAILABLE, revision 2
        val updatedLot2 = itemsMap["lot-2"]
        assertNotNull(updatedLot2)
        assertEquals(0.5, updatedLot2!!.quantity!!, 0.001)
        assertEquals(ItemStatus.AVAILABLE, updatedLot2.status)
        assertEquals(2L, updatedLot2.revision)

        // Exactly 2 canonical SET_QUANTITY operations emitted (1 for Lot 1, 1 for Lot 2)
        assertEquals(2, ops.size)

        val op1 = ops[0]
        assertEquals("lot-1", op1.targetItemId)
        assertEquals(1L, op1.canonicalSequence)
        val payload1 = json.decodeFromString<SetQuantityPayloadDto>(op1.payloadJson)
        assertEquals(0.0, payload1.absoluteQuantity, 0.001)

        val op2 = ops[1]
        assertEquals("lot-2", op2.targetItemId)
        assertEquals(2L, op2.canonicalSequence)
        val payload2 = json.decodeFromString<SetQuantityPayloadDto>(op2.payloadJson)
        assertEquals(0.5, payload2.absoluteQuantity, 0.001)
    }
}
