package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Stock counted in packs against recipes that count units, or weigh what is in the pack. */
class CookingFromPacksTest {
    private val mealDao = FakeMealDao()
    private val inventoryDao = FakeInventoryDao()
    private val transactionDao = FakeTransactionDao()
    private val leftoverDao = FakeLeftoverDao()
    private val runner = FakeTransactionRunner(
        fakeMealDao = mealDao, fakeInventoryDao = inventoryDao, fakeTransactionDao = transactionDao,
        fakeShoppingDao = FakeShoppingDao(), fakeStapleRuleDao = FakeStapleRuleDao(), fakeLeftoverDao = leftoverDao
    )
    private val meals = MealDomainServiceImpl(mealDao, inventoryDao, transactionDao, leftoverDao, runner)

    private suspend fun cook(quantity: Double, unit: QuantityUnit): DomainResult<Unit> {
        meals.createPlannedMeal(
            Meal(id = "m", date = LocalDate.now().toString(), title = "Dinner"),
            listOf(MealIngredient(id = "i", mealId = "m", canonicalIngredientId = null, displayName = "Chicken breast", plannedQuantity = quantity, plannedUnit = unit))
        )
        return meals.markMealCooked("m")
    }

    private suspend fun lots() = inventoryDao.getAllItemsSync()

    @Test
    fun aUnitIsTakenFromAPack() = runTest {
        inventoryDao.insertItem(stock(2.0, QuantityUnit.PACK, null, null, null))
        val result = cook(1.0, QuantityUnit.UNIT)
        assertTrue("$result", result is DomainResult.Success)
        assertEquals(1.0, lots().single().quantity!!, 0.001)
    }

    @Test
    fun countedPacksWithAWeightAreNotSplitIntoHundredsOfPacks() = runTest {
        inventoryDao.insertItem(stock(2.0, QuantityUnit.PACK, 2, 500.0, QuantityUnit.GRAM))
        val result = cook(1.0, QuantityUnit.UNIT)
        assertTrue("$result", result is DomainResult.Success)
        assertEquals(1.0, lots().single().quantity!!, 0.001)
    }

    @Test
    fun aUnitAgainstAWeighedBagCooksTheMealAndLeavesTheBagAlone() = runTest {
        inventoryDao.insertItem(stock(1000.0, QuantityUnit.GRAM, null, 1000.0, QuantityUnit.GRAM))
        meals.createPlannedMeal(
            Meal(id = "n", date = LocalDate.now().toString(), title = "Soup"),
            listOf(MealIngredient(id = "j", mealId = "n", canonicalIngredientId = null, displayName = "Chicken breast", plannedQuantity = 1.0, plannedUnit = QuantityUnit.UNIT))
        )
        assertEquals(listOf("Chicken breast (pantry in gram, recipe in unit)"), meals.stockNotCompared("n"))
        val result = meals.markMealCooked("n")
        assertTrue("$result", result is DomainResult.Success)
        assertEquals(1000.0, lots().single().quantity!!, 0.001)
        assertEquals(ItemStatus.AVAILABLE, lots().single().status)
    }

    @Test
    fun gramsComeOutOfAPackOfKnownWeight() = runTest {
        inventoryDao.insertItem(stock(1.0, QuantityUnit.PACK, null, 500.0, QuantityUnit.GRAM))
        val result = cook(200.0, QuantityUnit.GRAM)
        assertTrue("$result", result is DomainResult.Success)
        assertEquals(0.6, lots().single().quantity!!, 0.001)
    }

    private fun stock(qty: Double, unit: QuantityUnit, packCount: Int?, packSize: Double?, packSizeUnit: QuantityUnit?) = InventoryItemEntity(
        id = "lot", barcode = null, canonicalIngredientId = null, canonicalName = "chicken breast",
        displayName = "Chicken breast", brand = null, category = FoodCategory.FRIDGE,
        storageLocation = StorageLocation.FRIDGE, quantity = qty, quantityUnit = unit,
        packCount = packCount, packSize = packSize, packSizeUnit = packSizeUnit, quantityConfidence = QuantityConfidence.EXACT,
        status = ItemStatus.AVAILABLE, opened = false, frozen = false, staple = false, purchaseDate = null,
        openedDate = null, useByDate = null, bestBeforeDate = null, notes = null,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
    )
}
