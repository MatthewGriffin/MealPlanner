package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

@RunWith(JUnit4::class)
class Phase2aRoomMigrationAndRevisionTest {

    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var inventoryBarcodeDao: FakeInventoryBarcodeDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var transactionRunner: FakeTransactionRunner
    private lateinit var domainService: InventoryDomainServiceImpl

    @Before
    fun setUp() {
        inventoryDao = FakeInventoryDao()
        inventoryBarcodeDao = FakeInventoryBarcodeDao()
        transactionDao = FakeTransactionDao()
        stapleRuleDao = FakeStapleRuleDao()
        transactionRunner = FakeTransactionRunner()

        domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = inventoryBarcodeDao,
            transactionDao = transactionDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = transactionRunner
        )
    }

    @Test
    fun newItem_beginsWithRevisionOne() = runTest {
        val item = InventoryItem(
            canonicalName = "Milk",
            displayName = "Whole Milk",
            category = FoodCategory.FRESH_PANTRY,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 2.0,
            quantityUnit = QuantityUnit.LITRE
        )

        domainService.addItem(item)

        val inserted = inventoryDao.getItemById(item.id)
        assertNotNull(inserted)
        assertEquals(1L, inserted?.revision)
    }

    @Test
    fun updateItem_incrementsRevisionByOne() = runTest {
        val item = InventoryItem(
            id = "item_rev_1",
            canonicalName = "Rice",
            displayName = "Basmati Rice",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.KILOGRAM,
            revision = 1L
        )
        domainService.addItem(item)

        val updatedItem = item.copy(displayName = "Organic Basmati Rice")
        domainService.updateItem(updatedItem)

        val fetched = inventoryDao.getItemById("item_rev_1")
        assertEquals("Revision must increment to 2", 2L, fetched?.revision)
    }

    @Test
    fun updateItem_ignoresCallerSubmittedRevision_andDerivesFromPersistedEntity() = runTest {
        val item = InventoryItem(
            id = "item_rev_boundary",
            canonicalName = "Sugar",
            displayName = "Caster Sugar",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.KILOGRAM,
            revision = 1L
        )
        domainService.addItem(item)

        val tamperedCallerItem = item.copy(displayName = "Fine Caster Sugar", revision = 999L)
        domainService.updateItem(tamperedCallerItem)

        val fetched = inventoryDao.getItemById("item_rev_boundary")
        assertEquals("Revision must derive from persisted entity revision + 1", 2L, fetched?.revision)
    }

    @Test
    fun userCorrection_incrementsRevisionByOne() = runTest {
        val item = InventoryItem(
            id = "item_rev_2",
            canonicalName = "Pasta",
            displayName = "Penne",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 500.0,
            quantityUnit = QuantityUnit.GRAM,
            revision = 1L
        )
        domainService.addItem(item)

        domainService.recordUserCorrection("item_rev_2", 1000.0, QuantityUnit.GRAM, QuantityConfidence.EXACT)

        val fetched = inventoryDao.getItemById("item_rev_2")
        assertEquals(2L, fetched?.revision)
    }

    @Test
    fun discardItem_incrementsRevisionByOne() = runTest {
        val item = InventoryItem(
            id = "item_rev_3",
            canonicalName = "Yogurt",
            displayName = "Greek Yogurt",
            category = FoodCategory.FRESH_PANTRY,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = QuantityUnit.PACK,
            revision = 2L
        )
        domainService.addItem(item)

        domainService.discardItem("item_rev_3", "Expired")

        val fetched = inventoryDao.getItemById("item_rev_3")
        assertEquals(ItemStatus.DISCARDED, fetched?.status)
        assertEquals(3L, fetched?.revision)
    }

    @Test
    fun mergeItems_incrementsRevisionsForSurvivorAndMergedItem() = runTest {
        val survivor = InventoryItem(
            id = "survivor_1",
            canonicalName = "Tomatoes",
            displayName = "Tinned Tomatoes",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            revision = 1L
        )
        val merged = InventoryItem(
            id = "merged_1",
            canonicalName = "Tomatoes",
            displayName = "Chopped Tomatoes",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.UNIT,
            revision = 4L
        )
        domainService.addItem(survivor)
        domainService.addItem(merged)

        domainService.mergeInventoryItems("survivor_1", "merged_1", "Consolidated Tomatoes")

        val fetchedSurvivor = inventoryDao.getItemById("survivor_1")
        val fetchedMerged = inventoryDao.getItemById("merged_1")

        assertEquals(2L, fetchedSurvivor?.revision)
        assertEquals(5L, fetchedMerged?.revision)
        assertEquals(ItemStatus.DISCARDED, fetchedMerged?.status)
    }
}
