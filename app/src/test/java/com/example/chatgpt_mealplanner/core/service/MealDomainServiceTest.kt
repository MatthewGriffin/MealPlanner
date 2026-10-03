package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.ActualIngredientOverride
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.IngredientSubstitution
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.Leftover
import com.example.chatgpt_mealplanner.core.model.LeftoverStatus
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MealDomainServiceTest {

    private lateinit var mealDao: FakeMealDao
    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var leftoverDao: FakeLeftoverDao
    private lateinit var service: MealDomainServiceImpl

    @Before
    fun setup() {
        mealDao = FakeMealDao()
        inventoryDao = FakeInventoryDao()
        transactionDao = FakeTransactionDao()
        leftoverDao = FakeLeftoverDao()

        val runner = FakeTransactionRunner(mealDao, inventoryDao, transactionDao, leftoverDao)
        service = MealDomainServiceImpl(mealDao, inventoryDao, transactionDao, leftoverDao, runner)
    }

    @Test
    fun createPlannedMeal_createsMealWithoutAlteringInventory() = runTest {
        val meal = Meal(id = "m1", date = "2026-10-01", title = "Spaghetti Bolognese")
        val ing = MealIngredient(
            id = "i1",
            mealId = "m1",
            canonicalIngredientId = "PASTA",
            displayName = "Spaghetti",
            plannedQuantity = 200.0,
            plannedUnit = QuantityUnit.GRAM
        )

        val result = service.createPlannedMeal(meal, listOf(ing))
        assertTrue(result is DomainResult.Success)

        val storedMeal = mealDao.getMealWithIngredients("m1")
        assertNotNull(storedMeal)
        assertEquals(MealStatus.PLANNED, storedMeal!!.meal.status)

        // Verify zero inventory transactions created
        val usableItems = inventoryDao.getUsableItems().first()
        assertTrue(usableItems.isEmpty())
    }

    @Test
    fun markMealCooked_deductsExactPlannedQuantities() = runTest {
        val pastaLot = InventoryItemEntity(
            id = "lot_pasta",
            barcode = null,
            canonicalIngredientId = "PASTA",
            canonicalName = "Spaghetti",
            displayName = "Spaghetti 500g",
            brand = null,
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 500.0,
            quantityUnit = QuantityUnit.GRAM,
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
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(pastaLot)

        val meal = Meal(id = "m2", date = "2026-10-01", title = "Pasta")
        val ing = MealIngredient(
            id = "i2",
            mealId = "m2",
            canonicalIngredientId = "PASTA",
            displayName = "Spaghetti",
            plannedQuantity = 200.0,
            plannedUnit = QuantityUnit.GRAM
        )
        service.createPlannedMeal(meal, listOf(ing))

        val result = service.markMealCooked("m2")
        assertTrue(result is DomainResult.Success)

        val updatedLot = inventoryDao.getItemById("lot_pasta")
        assertEquals(300.0, updatedLot!!.quantity!!, 0.001)

        val cookedMeal = mealDao.getMealWithIngredients("m2")
        assertEquals(MealStatus.COOKED, cookedMeal!!.meal.status)
    }

    @Test
    fun markMealCooked_twice_returnsDuplicateOperationError() = runTest {
        val pastaLot = InventoryItemEntity(
            id = "lot_pasta",
            barcode = null,
            canonicalIngredientId = "PASTA",
            canonicalName = "Spaghetti",
            displayName = "Spaghetti 500g",
            brand = null,
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 500.0,
            quantityUnit = QuantityUnit.GRAM,
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
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(pastaLot)

        val meal = Meal(id = "m3", date = "2026-10-01", title = "Pasta")
        val ing = MealIngredient(
            id = "i3",
            mealId = "m3",
            canonicalIngredientId = "PASTA",
            displayName = "Spaghetti",
            plannedQuantity = 200.0,
            plannedUnit = QuantityUnit.GRAM
        )
        service.createPlannedMeal(meal, listOf(ing))

        val first = service.markMealCooked("m3")
        assertTrue(first is DomainResult.Success)

        val second = service.markMealCooked("m3")
        assertTrue(second is DomainResult.Failure)
        val failure = second as DomainResult.Failure
        assertTrue(failure.error is DomainError.DuplicateOperation)
    }

    @Test
    fun markMealCooked_actualQuantityOverride_deductsActualNotPlanned() = runTest {
        val cheeseLot = InventoryItemEntity(
            id = "lot_cheese",
            barcode = null,
            canonicalIngredientId = "CHEDDAR",
            canonicalName = "Cheddar",
            displayName = "Cheddar Block",
            brand = null,
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 400.0,
            quantityUnit = QuantityUnit.GRAM,
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
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(cheeseLot)

        val meal = Meal(id = "m4", date = "2026-10-01", title = "Mac Cheese")
        val ing = MealIngredient(
            id = "i4",
            mealId = "m4",
            canonicalIngredientId = "CHEDDAR",
            displayName = "Cheddar",
            plannedQuantity = 200.0,
            plannedUnit = QuantityUnit.GRAM
        )
        service.createPlannedMeal(meal, listOf(ing))

        val override = ActualIngredientOverride(
            mealIngredientId = "i4",
            actualQuantity = 150.0,
            actualUnit = QuantityUnit.GRAM
        )

        val result = service.markMealCooked("m4", actualIngredientOverrides = listOf(override))
        assertTrue(result is DomainResult.Success)

        val updatedLot = inventoryDao.getItemById("lot_cheese")
        assertEquals(250.0, updatedLot!!.quantity!!, 0.001)
    }

    @Test
    fun multipackSplitting_caseA_exactInnerUnit_noResidualOpenedLot() = runTest {
        val tinLot = InventoryItemEntity(
            id = "lot_tins",
            barcode = null,
            canonicalIngredientId = "BAKED_BEANS",
            canonicalName = "Baked Beans",
            displayName = "Heinz Beans 2x405g",
            brand = null,
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 810.0,
            quantityUnit = QuantityUnit.GRAM,
            packCount = 2,
            packSize = 405.0,
            packSizeUnit = QuantityUnit.GRAM,
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
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(tinLot)

        val meal = Meal(id = "m_a", date = "2026-10-01", title = "Beans on Toast")
        val ing = MealIngredient(
            id = "i_a",
            mealId = "m_a",
            canonicalIngredientId = "BAKED_BEANS",
            displayName = "Baked Beans",
            plannedQuantity = 405.0,
            plannedUnit = QuantityUnit.GRAM
        )
        service.createPlannedMeal(meal, listOf(ing))

        val result = service.markMealCooked("m_a")
        assertTrue(result is DomainResult.Success)

        val updatedUnopened = inventoryDao.getItemById("lot_tins")
        assertEquals(1, updatedUnopened!!.packCount)
        assertEquals(405.0, updatedUnopened.quantity!!, 0.001)

        val allItems = inventoryDao.getUsableItems().first()
        assertEquals(1, allItems.size) // No residual opened lot created
    }

    @Test
    fun multipackSplitting_caseB_partialInnerUnit_createsOpenedResidualLot() = runTest {
        val tinLot = InventoryItemEntity(
            id = "lot_tins2",
            barcode = null,
            canonicalIngredientId = "BAKED_BEANS",
            canonicalName = "Baked Beans",
            displayName = "Heinz Beans 2x405g",
            brand = null,
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 810.0,
            quantityUnit = QuantityUnit.GRAM,
            packCount = 2,
            packSize = 405.0,
            packSizeUnit = QuantityUnit.GRAM,
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
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(tinLot)

        val meal = Meal(id = "m_b", date = "2026-10-01", title = "Beans Portion")
        val ing = MealIngredient(
            id = "i_b",
            mealId = "m_b",
            canonicalIngredientId = "BAKED_BEANS",
            displayName = "Baked Beans",
            plannedQuantity = 235.0,
            plannedUnit = QuantityUnit.GRAM
        )
        service.createPlannedMeal(meal, listOf(ing))

        val result = service.markMealCooked("m_b")
        assertTrue(result is DomainResult.Success)

        val updatedUnopened = inventoryDao.getItemById("lot_tins2")
        assertEquals(1, updatedUnopened!!.packCount)
        assertEquals(405.0, updatedUnopened.quantity!!, 0.001)

        val allUsable = inventoryDao.getUsableItems().first()
        assertEquals(2, allUsable.size)

        val openedLot = allUsable.find { it.opened == true }
        assertNotNull(openedLot)
        assertEquals(170.0, openedLot!!.quantity!!, 0.001) // 405 - 235 = 170g
        assertNull(openedLot.packCount)

        // Total remaining = 405 + 170 = 575g
        val totalUsable = allUsable.sumOf { it.quantity ?: 0.0 }
        assertEquals(575.0, totalUsable, 0.001)
    }

    @Test
    fun multipackSplitting_caseC_consumesOneFullUnitAndSplitsSecondUnit() = runTest {
        val tinLot = InventoryItemEntity(
            id = "lot_tins3",
            barcode = null,
            canonicalIngredientId = "BAKED_BEANS",
            canonicalName = "Baked Beans",
            displayName = "Heinz Beans 2x405g",
            brand = null,
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 810.0,
            quantityUnit = QuantityUnit.GRAM,
            packCount = 2,
            packSize = 405.0,
            packSizeUnit = QuantityUnit.GRAM,
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
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(tinLot)

        val meal = Meal(id = "m_c", date = "2026-10-01", title = "Chilli Beans")
        val ing = MealIngredient(
            id = "i_c",
            mealId = "m_c",
            canonicalIngredientId = "BAKED_BEANS",
            displayName = "Baked Beans",
            plannedQuantity = 640.0,
            plannedUnit = QuantityUnit.GRAM
        )
        service.createPlannedMeal(meal, listOf(ing))

        val result = service.markMealCooked("m_c")
        assertTrue(result is DomainResult.Success)

        val updatedUnopened = inventoryDao.getItemById("lot_tins3")
        assertEquals(0, updatedUnopened!!.packCount)
        assertEquals(ItemStatus.OUT, updatedUnopened.status)

        val allUsable = inventoryDao.getUsableItems().first()
        assertEquals(1, allUsable.size)

        val openedLot = allUsable.first()
        assertTrue(openedLot.opened == true)
        assertEquals(170.0, openedLot.quantity!!, 0.001) // 810 - 640 = 170g
    }

    @Test
    fun markMealCooked_criticalAtomicRollback_whenOneIngredientFails_rollsBackEverything() = runTest {
        val riceLot = InventoryItemEntity(
            id = "lot_rice",
            barcode = null,
            canonicalIngredientId = "RICE",
            canonicalName = "Basmati Rice",
            displayName = "Rice 1kg",
            brand = null,
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1000.0,
            quantityUnit = QuantityUnit.GRAM,
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
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(riceLot)

        // Meal requires 200g Rice (available) AND 500g Chicken (0g available in stock)
        val meal = Meal(id = "m_rollback", date = "2026-10-01", title = "Chicken Rice")
        val ing1 = MealIngredient(
            id = "ing_rice",
            mealId = "m_rollback",
            canonicalIngredientId = "RICE",
            displayName = "Basmati Rice",
            plannedQuantity = 200.0,
            plannedUnit = QuantityUnit.GRAM
        )
        val ing2 = MealIngredient(
            id = "ing_chicken",
            mealId = "m_rollback",
            canonicalIngredientId = "CHICKEN",
            displayName = "Chicken Breast",
            plannedQuantity = 500.0,
            plannedUnit = QuantityUnit.GRAM
        )
        service.createPlannedMeal(meal, listOf(ing1, ing2))

        val result = service.markMealCooked("m_rollback")
        assertTrue(result is DomainResult.Failure)
        val failure = result as DomainResult.Failure
        assertTrue(failure.error is DomainError.InsufficientQuantity)

        // Verify Rice quantity remains 100% UNTOUCHED (1000g)
        val untouchedRice = inventoryDao.getItemById("lot_rice")
        assertEquals(1000.0, untouchedRice!!.quantity!!, 0.001)

        // Verify meal status remains PLANNED
        val storedMeal = mealDao.getMealWithIngredients("m_rollback")
        assertEquals(MealStatus.PLANNED, storedMeal!!.meal.status)

        // Verify zero inventory transactions created
        val transactions = transactionDao.getTransactionsForMeal("m_rollback")
        assertTrue(transactions.isEmpty())

        // Verify zero leftovers created
        val leftovers = leftoverDao.getLeftoverById("l1")
        assertNull(leftovers)
    }

    @Test
    fun markMealCooked_withLeftoverPortions_createsLeftoverEntity() = runTest {
        val pastaLot = InventoryItemEntity(
            id = "lot_pasta2",
            barcode = null,
            canonicalIngredientId = "PASTA",
            canonicalName = "Spaghetti",
            displayName = "Spaghetti 500g",
            brand = null,
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 500.0,
            quantityUnit = QuantityUnit.GRAM,
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
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(pastaLot)

        // Batch of 4 portions: 2 immediate, 2 leftovers
        val meal = Meal(
            id = "m_batch",
            date = "2026-10-01",
            title = "Batch Pasta",
            batchPortions = 4,
            immediatePortions = 2,
            leftoverPortions = 2
        )
        val ing = MealIngredient(
            id = "i_batch",
            mealId = "m_batch",
            canonicalIngredientId = "PASTA",
            displayName = "Spaghetti",
            plannedQuantity = 400.0,
            plannedUnit = QuantityUnit.GRAM
        )
        service.createPlannedMeal(meal, listOf(ing))

        val result = service.markMealCooked("m_batch", leftoverStorageLocation = StorageLocation.FREEZER)
        assertTrue(result is DomainResult.Success)

        val leftovers = leftoverDao.observeAvailableLeftovers().first()
        assertEquals(1, leftovers.size)
        val leftover = leftovers.first()
        assertEquals("Batch Pasta", leftover.name)
        assertEquals(2, leftover.initialPortions)
        assertEquals(2, leftover.portions)
        assertEquals(StorageLocation.FREEZER, leftover.storageLocation)
    }

    @Test
    fun consumeLeftoverPortions_reducesPortionsWithoutAlteringRawInventory() = runTest {
        val leftover = Leftover(
            id = "leftover_1",
            sourceMealId = null,
            name = "Curry Leftover",
            initialPortions = 2,
            portions = 2,
            createdDate = "2026-10-01"
        )
        leftoverDao.insertLeftover(leftover.toEntity())

        val result = service.consumeLeftoverPortions("leftover_1", 1)
        assertTrue(result is DomainResult.Success)

        val updatedLeftover = leftoverDao.getLeftoverById("leftover_1")
        assertEquals(1, updatedLeftover!!.portions)
        assertEquals(2, updatedLeftover.initialPortions)
        assertEquals(LeftoverStatus.AVAILABLE, updatedLeftover.status)

        // Consume remaining portion
        service.consumeLeftoverPortions("leftover_1", 1)
        val consumedLeftover = leftoverDao.getLeftoverById("leftover_1")
        assertEquals(0, consumedLeftover!!.portions)
        assertEquals(LeftoverStatus.CONSUMED, consumedLeftover.status)
    }

    @Test
    fun substitution_validXorRule_rejectsInvalidTarget() = runTest {
        val ing = MealIngredient(
            id = "i_sub",
            mealId = "m_sub",
            canonicalIngredientId = "BROCCOLI",
            displayName = "Broccoli",
            plannedQuantity = 200.0,
            plannedUnit = QuantityUnit.GRAM
        )
        val meal = Meal(id = "m_sub", date = "2026-10-01", title = "Sub Meal")
        service.createPlannedMeal(meal, listOf(ing))

        // Invalid substitution with BOTH target fields populated
        val invalidSub = IngredientSubstitution(
            mealIngredientId = "i_sub",
            selectedCanonicalIngredientId = "GREEN_BEANS",
            selectedInventoryItemId = "some_lot_id",
            actualQuantity = 200.0,
            actualUnit = QuantityUnit.GRAM
        )

        val result = service.markMealCooked("m_sub", substitutions = listOf(invalidSub))
        assertTrue(result is DomainResult.Failure)
        val failure = result as DomainResult.Failure
        assertTrue(failure.error is DomainError.TransactionFailed)
    }

    @Test
    fun approximateQuantity_deductsStock_andPreservesApproximateConfidence() = runTest {
        val approxLot = InventoryItemEntity(
            id = "lot_approx",
            barcode = null,
            canonicalIngredientId = "BROCCOLI",
            canonicalName = "Broccoli",
            displayName = "Frozen Broccoli 550g",
            brand = null,
            category = FoodCategory.FREEZER,
            storageLocation = StorageLocation.FREEZER,
            quantity = 550.0,
            quantityUnit = QuantityUnit.GRAM,
            packCount = null,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.APPROXIMATE,
            status = ItemStatus.AVAILABLE,
            opened = false,
            frozen = false,
            staple = false,
            purchaseDate = null,
            openedDate = null,
            useByDate = null,
            bestBeforeDate = null,
            notes = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        inventoryDao.insertItem(approxLot)

        val meal = Meal(id = "m_approx", date = "2026-10-01", title = "Broccoli Bake")
        val ing = MealIngredient(
            id = "i_approx",
            mealId = "m_approx",
            canonicalIngredientId = "BROCCOLI",
            displayName = "Broccoli",
            plannedQuantity = 200.0,
            plannedUnit = QuantityUnit.GRAM
        )
        service.createPlannedMeal(meal, listOf(ing))

        val result = service.markMealCooked("m_approx")
        assertTrue(result is DomainResult.Success)

        val updatedApprox = inventoryDao.getItemById("lot_approx")
        assertEquals(350.0, updatedApprox!!.quantity!!, 0.001)
        // Verify confidence was preserved as APPROXIMATE (never silently upgraded to EXACT)
        assertEquals(QuantityConfidence.APPROXIMATE, updatedApprox.quantityConfidence)
    }
}
