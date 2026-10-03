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
class InventoryDomainServiceTest {

    private val fakeInventoryDao = FakeInventoryDao()
    private val fakeInventoryBarcodeDao = FakeInventoryBarcodeDao()
    private val fakeTransactionDao = FakeTransactionDao()
    private val fakeStapleRuleDao = FakeStapleRuleDao()
    private val transactionRunner = FakeTransactionRunner()

    private lateinit var service: InventoryDomainServiceImpl

    @Before
    fun setup() {
        service = InventoryDomainServiceImpl(
            inventoryDao = fakeInventoryDao,
            inventoryBarcodeDao = fakeInventoryBarcodeDao,
            transactionDao = fakeTransactionDao,
            stapleRuleDao = fakeStapleRuleDao,
            transactionRunner = transactionRunner
        )
    }

    @Test
    fun deductQuantityForMeal_depletesQuantityAndSetsStatusOut() = runTest {
        val item = InventoryItem(
            id = "pasta-1",
            canonicalName = "Penne",
            displayName = "Penne Pasta",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 100.0,
            quantityUnit = QuantityUnit.GRAM,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE
        )
        fakeInventoryDao.insertItem(item.toEntity())

        val request = DeductionRequest(
            itemId = "pasta-1",
            amountToDeduct = 100.0,
            unit = QuantityUnit.GRAM,
            mealId = "meal-1"
        )

        val result = service.deductQuantityForMeal(request)
        assertTrue(result is DomainResult.Success)

        val updated = fakeInventoryDao.getItemById("pasta-1")
        assertEquals(0.0, updated?.quantity ?: -1.0, 0.001)
        assertEquals(ItemStatus.OUT, updated?.status)
    }

    @Test
    fun deductQuantityForMeal_duplicateRequest_returnsError() = runTest {
        val item = InventoryItem(
            id = "rice-1",
            canonicalName = "Rice",
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 500.0,
            quantityUnit = QuantityUnit.GRAM,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE
        )
        fakeInventoryDao.insertItem(item.toEntity())

        val request = DeductionRequest(
            itemId = "rice-1",
            amountToDeduct = 200.0,
            unit = QuantityUnit.GRAM,
            mealId = "meal-dup"
        )

        val first = service.deductQuantityForMeal(request)
        assertTrue(first is DomainResult.Success)

        val second = service.deductQuantityForMeal(request)
        assertTrue(second is DomainResult.Failure)
        assertTrue((second as DomainResult.Failure).error is DomainError.DuplicateOperation)
    }

    @Test
    fun recordUserCorrection_updatesQuantityAndUnitAtomically_withoutMassVolumeConversion() = runTest {
        val item = InventoryItem(
            id = "toothpaste-1",
            canonicalName = "Toothpaste",
            displayName = "Oral-B Toothpaste",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 75.0,
            quantityUnit = QuantityUnit.GRAM,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE
        )
        fakeInventoryDao.insertItem(item.toEntity())

        val result = service.recordUserCorrection(
            itemId = "toothpaste-1",
            newQuantity = 75.0,
            newUnit = QuantityUnit.MILLILITRE,
            newConfidence = QuantityConfidence.EXACT,
            note = "Correction 75 g -> 75 ml"
        )
        assertTrue(result is DomainResult.Success)

        val updated = fakeInventoryDao.getItemById("toothpaste-1")
        assertEquals(75.0, updated?.quantity ?: -1.0, 0.001)
        assertEquals(QuantityUnit.MILLILITRE, updated?.quantityUnit)

        val transactions = fakeTransactionDao.transactions.filter { it.inventoryItemId == "toothpaste-1" }
        assertEquals(1, transactions.size)
        assertEquals(QuantityUnit.MILLILITRE, transactions[0].unit)
        assertEquals(75.0, transactions[0].quantityChange ?: 0.0, 0.001)
    }
}
