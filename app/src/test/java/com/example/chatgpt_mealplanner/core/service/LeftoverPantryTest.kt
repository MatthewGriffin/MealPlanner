package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.util.LeftoverMeals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class LeftoverPantryTest {
    private val mealDao = FakeMealDao()
    private val inventoryDao = FakeInventoryDao()
    private val transactionDao = FakeTransactionDao()
    private val leftoverDao = FakeLeftoverDao()
    private val added = mutableListOf<InventoryItem>()
    private val calls = mutableListOf<String>()

    private val inventory: InventoryDomainService = Proxy.newProxyInstance(
        InventoryDomainService::class.java.classLoader, arrayOf(InventoryDomainService::class.java)
    ) { _, method, args ->
        calls += method.name
        if (method.name == "addItem") added += args[0] as InventoryItem
        DomainResult.Success(Unit)
    } as InventoryDomainService

    private val service = MealDomainServiceImpl(
        mealDao, inventoryDao, transactionDao, leftoverDao,
        FakeTransactionRunner(mealDao, inventoryDao, transactionDao, leftoverDao),
        inventoryDomainService = inventory
    )

    @Test
    fun cookingABatchAddsTheExtraPortionsToThePantry() = runTest {
        val meal = Meal(id = "m1", date = "2026-11-16", title = "Chicken risotto", servings = 2, batchPortions = 4, immediatePortions = 2, leftoverPortions = 2)
        service.createPlannedMeal(meal, emptyList())

        assertTrue(service.markMealCooked("m1") is DomainResult.Success)

        val item = added.single()
        assertEquals("Leftover Chicken risotto", item.displayName)
        assertEquals(2.0, item.quantity!!, 0.0)
        assertEquals(QuantityUnit.PORTION, item.quantityUnit)
        assertEquals(FoodCategory.READY_MEALS, item.category)
        assertEquals("m1", LeftoverMeals.sourceMealId(item.notes))
    }

    @Test
    fun cookingAnOrdinaryMealAddsNothing() = runTest {
        service.createPlannedMeal(Meal(id = "m2", date = "2026-11-16", title = "Toast"), emptyList())
        service.markMealCooked("m2")
        assertTrue(added.isEmpty())
    }

    @Test
    fun cookingAPlannedLeftoversDayDoesNotAddOrDeductIngredients() = runTest {
        val entry = Meal(id = "l1", date = "2026-11-17", title = "Leftovers: Chicken risotto", notes = LeftoverMeals.noteFor("m1"))
        service.createPlannedMeal(entry, emptyList())

        assertTrue(service.markMealCooked("l1") is DomainResult.Success)

        assertEquals(MealStatus.COOKED, mealDao.getMealWithIngredients("l1")!!.meal.status)
        assertTrue(added.isEmpty())
    }
}
