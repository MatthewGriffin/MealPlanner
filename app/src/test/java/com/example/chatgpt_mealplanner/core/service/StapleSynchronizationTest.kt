package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StapleRuleMode
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class StapleSynchronizationTest {

    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var inventoryBarcodeDao: FakeInventoryBarcodeDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var mealDao: FakeMealDao
    private lateinit var shoppingDao: FakeShoppingDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var runner: FakeTransactionRunner

    private lateinit var inventoryService: InventoryDomainServiceImpl

    @Before
    fun setup() {
        inventoryDao = FakeInventoryDao()
        inventoryBarcodeDao = FakeInventoryBarcodeDao()
        transactionDao = FakeTransactionDao()
        mealDao = FakeMealDao()
        shoppingDao = FakeShoppingDao()
        stapleRuleDao = FakeStapleRuleDao()

        runner = FakeTransactionRunner()

        inventoryService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = inventoryBarcodeDao,
            transactionDao = transactionDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )
    }

    @Test
    fun toggleStapleStatus_createsStapleRuleAndPersists() = runTest {
        val item = InventoryItem(
            id = "lot_beans",
            canonicalIngredientId = "CANONICAL_BEANS",
            canonicalName = "Baked Beans",
            displayName = "Baked Beans 400g",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.TIN,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            staple = false
        )
        inventoryDao.insertItem(item.toEntity())

        val toggleResult = inventoryService.toggleStapleStatus("lot_beans")
        assertTrue(toggleResult is DomainResult.Success)
        assertTrue((toggleResult as DomainResult.Success).value)

        val updatedItem = inventoryDao.getItemById("lot_beans")
        assertNotNull(updatedItem)
        assertTrue(updatedItem!!.staple)

        val createdRule = stapleRuleDao.getStapleRuleByCanonicalIngredientId("CANONICAL_BEANS")
        assertNotNull(createdRule)
        assertTrue(createdRule!!.enabled)
    }
}
