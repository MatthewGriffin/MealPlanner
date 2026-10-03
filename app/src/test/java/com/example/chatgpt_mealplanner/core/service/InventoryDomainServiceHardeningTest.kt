package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.DeductionRequest
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class InventoryDomainServiceHardeningTest {

    private val fakeInventoryDao = FakeInventoryDao()
    private val fakeInventoryBarcodeDao = FakeInventoryBarcodeDao()
    private val fakeTransactionDao = FakeTransactionDao()
    private val fakeStapleRuleDao = FakeStapleRuleDao()
    private val fakeTransactionRunner = FakeTransactionRunner()

    private lateinit var service: InventoryDomainServiceImpl

    @Before
    fun setup() {
        service = InventoryDomainServiceImpl(
            inventoryDao = fakeInventoryDao,
            inventoryBarcodeDao = fakeInventoryBarcodeDao,
            transactionDao = fakeTransactionDao,
            stapleRuleDao = fakeStapleRuleDao,
            transactionRunner = fakeTransactionRunner
        )
    }

    @Test
    fun deductQuantityForMeal_validExactDeduction_succeeds() = runTest {
        val item = InventoryItem(
            id = "item-1",
            canonicalName = "Milk",
            displayName = "Whole Milk",
            category = FoodCategory.FRIDGE,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1000.0,
            quantityUnit = QuantityUnit.MILLILITRE,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE
        )
        fakeInventoryDao.insertItem(item.toEntity())

        val req = DeductionRequest("item-1", 500.0, QuantityUnit.MILLILITRE, "meal-1")
        val res = service.deductQuantityForMeal(req)

        assertTrue(res is DomainResult.Success)
        val updated = fakeInventoryDao.getItemById("item-1")
        assertEquals(500.0, updated?.quantity ?: 0.0, 0.01)
    }
}
