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

/** A meal planned for later and cooked early must not put its ingredients back on the shopping list. */
class ShoppingAfterCookingTest {
    private val mealDao = FakeMealDao()
    private val inventoryDao = FakeInventoryDao()
    private val transactionDao = FakeTransactionDao()
    private val leftoverDao = FakeLeftoverDao()
    private val shoppingDao = FakeShoppingDao()
    private val stapleRuleDao = FakeStapleRuleDao()
    private val runner = FakeTransactionRunner(
        fakeMealDao = mealDao, fakeInventoryDao = inventoryDao, fakeTransactionDao = transactionDao,
        fakeShoppingDao = shoppingDao, fakeStapleRuleDao = stapleRuleDao, fakeLeftoverDao = leftoverDao
    )
    private val meals = MealDomainServiceImpl(mealDao, inventoryDao, transactionDao, leftoverDao, runner)
    private val shopping = ShoppingDomainServiceImpl(
        shoppingDao = shoppingDao, mealDao = mealDao, inventoryDao = inventoryDao,
        stapleRuleDao = stapleRuleDao, transactionRunner = runner
    )

    private fun chicken(mealId: String, id: String) = MealIngredient(
        id = id, mealId = mealId, canonicalIngredientId = null, displayName = "Chicken breast",
        plannedQuantity = 500.0, plannedUnit = QuantityUnit.GRAM
    )

    @Test
    fun cookingEarlyLeavesNothingToBuyForTheCookedMeal() = runTest {
        val friday = LocalDate.now().plusDays(4).toString()
        val saturday = LocalDate.now().plusDays(5).toString()
        meals.createPlannedMeal(Meal(id = "a", date = friday, title = "Chicken pie"), listOf(chicken("a", "ia")))
        meals.createPlannedMeal(Meal(id = "b", date = saturday, title = "Chicken curry"), listOf(chicken("b", "ib")))
        inventoryDao.insertItem(stock(1000.0))

        assertTrue(shopping.calculateShoppingRequirements(null, null).let { it is DomainResult.Success && it.value.isEmpty() })

        assertTrue(meals.markMealCooked("a") is DomainResult.Success)

        val after = (shopping.calculateShoppingRequirements(null, null) as DomainResult.Success).value
        assertTrue("nothing should be short: $after", after.isEmpty())
        val regenerated = (shopping.generateOrRegenerateShoppingList() as DomainResult.Success).value
        assertEquals(0, regenerated.items.size)
    }

    private fun stock(qty: Double) = InventoryItemEntity(
        id = "lot", barcode = null, canonicalIngredientId = null, canonicalName = "chicken breast",
        displayName = "Chicken breast", brand = null, category = FoodCategory.FRIDGE,
        storageLocation = StorageLocation.FRIDGE, quantity = qty, quantityUnit = QuantityUnit.GRAM,
        packCount = null, packSize = null, packSizeUnit = null, quantityConfidence = QuantityConfidence.EXACT,
        status = ItemStatus.AVAILABLE, opened = false, frozen = false, staple = false, purchaseDate = null,
        openedDate = null, useByDate = null, bestBeforeDate = null, notes = null,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
    )
}
