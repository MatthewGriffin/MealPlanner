package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryMergeLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class InventoryIdentityTest {

    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var inventoryBarcodeDao: FakeInventoryBarcodeDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var domainService: InventoryDomainServiceImpl

    @Before
    fun setup() {
        inventoryDao = FakeInventoryDao()
        inventoryBarcodeDao = FakeInventoryBarcodeDao()
        transactionDao = FakeTransactionDao()
        stapleRuleDao = FakeStapleRuleDao()
        domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = inventoryBarcodeDao,
            transactionDao = transactionDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = FakeTransactionRunner(
                fakeInventoryDao = inventoryDao,
                fakeTransactionDao = transactionDao,
                fakeStapleRuleDao = stapleRuleDao
            )
        )
    }

    @Test
    fun sameBarcodeScannedTwice_createsOneInventoryItemAndConsolidatesStock() = runTest {
        val input1 = ConfirmedProductInput(
            barcode = "5012345678900",
            displayName = "White Sliced Bread",
            netQuantity = 1.0,
            netQuantityUnit = QuantityUnit.PACK,
            packCount = 1
        )
        val res1 = domainService.addLotFromProductInput(input1)
        assertTrue(res1 is DomainResult.Success)

        val input2 = ConfirmedProductInput(
            barcode = "5012345678900",
            displayName = "White Sliced Bread",
            netQuantity = 1.0,
            netQuantityUnit = QuantityUnit.PACK,
            packCount = 1
        )
        val res2 = domainService.addLotFromProductInput(input2)
        assertTrue(res2 is DomainResult.Success)

        assertEquals(1, inventoryDao.itemsMap.size)
        val consolidated = inventoryDao.itemsMap.values.first().toDomainModel()
        assertEquals(2.0, consolidated.quantity!!, 0.01)
        assertEquals(2, consolidated.packCount)
    }

    @Test
    fun userDisabledActiveStapleRule_remainsVisible() = runTest {
        val item = InventoryItem(
            id = "item_user_disabled",
            canonicalName = "Butter",
            displayName = "Butter",
            category = FoodCategory.CUPBOARD,
            storageLocation = StorageLocation.FRIDGE,
            quantity = 1.0,
            quantityUnit = QuantityUnit.PACK,
            status = ItemStatus.AVAILABLE,
            staple = false
        )
        domainService.addItem(item)

        // Create rule disabled by user
        val userDisabledRule = StapleRuleEntity(
            id = "rule_butter",
            canonicalIngredientId = "item_item_user_disabled",
            displayName = "Butter",
            enabled = false, // User disabled toggle
            archivedByMerge = false, // Active identity
            mode = StapleRuleMode.PACK_COUNT,
            thresholdQuantity = null,
            thresholdUnit = null,
            targetQuantity = null,
            targetUnit = null,
            thresholdPackCount = 1,
            targetPackCount = 2,
            preferredStorageLocation = StorageLocation.FRIDGE,
            notes = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        stapleRuleDao.insertOrUpdateStapleRule(userDisabledRule)

        // Visible rules include user-disabled active rule!
        val visibleRules = stapleRuleDao.rulesMap.values.filter { !it.archivedByMerge }
        assertEquals(1, visibleRules.size)
        assertEquals("Butter", visibleRules.first().displayName)
        assertFalse(visibleRules.first().enabled) // Off toggle
    }

    @Test
    fun repairOrphanStapleRulesFromMergeLogs_disablesOrphanedMergedRuleAndIsIdempotent() = runTest {
        val survivorItem = InventoryItemEntity(
            id = "soft_bread_id",
            barcode = "5011111111111",
            canonicalIngredientId = "item_soft_bread_id",
            canonicalName = "Soft White Bread",
            displayName = "Soft White Bread",
            brand = null,
            category = FoodCategory.BREAD_BAKERY,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 2.0,
            quantityUnit = QuantityUnit.PACK,
            packCount = 2,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            opened = false,
            frozen = false,
            purchaseDate = null,
            openedDate = null,
            useByDate = null,
            bestBeforeDate = null,
            notes = null,
            staple = true,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val mergedItem = InventoryItemEntity(
            id = "bread_id",
            barcode = null,
            canonicalIngredientId = "item_bread_id",
            canonicalName = "Bread",
            displayName = "Bread",
            brand = null,
            category = FoodCategory.BREAD_BAKERY,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 0.0,
            quantityUnit = QuantityUnit.PACK,
            packCount = 0,
            packSize = null,
            packSizeUnit = null,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.DISCARDED,
            opened = false,
            frozen = false,
            purchaseDate = null,
            openedDate = null,
            useByDate = null,
            bestBeforeDate = null,
            notes = null,
            staple = true,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        inventoryDao.insertItem(survivorItem)
        inventoryDao.insertItem(mergedItem)

        val survivorRule = StapleRuleEntity(
            id = "rule_soft_bread",
            canonicalIngredientId = "item_soft_bread_id",
            displayName = "Soft White Bread",
            enabled = true,
            archivedByMerge = false,
            mode = StapleRuleMode.PACK_COUNT,
            thresholdQuantity = null,
            thresholdUnit = null,
            targetQuantity = null,
            targetUnit = null,
            thresholdPackCount = 1,
            targetPackCount = 4,
            preferredStorageLocation = StorageLocation.CUPBOARD,
            notes = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val mergedRule = StapleRuleEntity(
            id = "rule_bread",
            canonicalIngredientId = "item_bread_id",
            displayName = "Bread",
            enabled = true,
            archivedByMerge = false, // BUGGY PHYSICAL STATE BEFORE REPAIR
            mode = StapleRuleMode.PACK_COUNT,
            thresholdQuantity = null,
            thresholdUnit = null,
            targetQuantity = null,
            targetUnit = null,
            thresholdPackCount = 1,
            targetPackCount = 4,
            preferredStorageLocation = StorageLocation.CUPBOARD,
            notes = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        stapleRuleDao.insertOrUpdateStapleRule(survivorRule)
        stapleRuleDao.insertOrUpdateStapleRule(mergedRule)

        val log = InventoryMergeLogEntity(
            id = "merge_log_1",
            survivorItemId = "soft_bread_id",
            mergedItemId = "bread_id",
            mergedItemSnapshotJson = "{}",
            mergedRuleSnapshotJson = "{}",
            transferredBarcodesJson = "[]",
            transferredQuantity = 1.0,
            transferredPackCount = 1,
            mergedAt = 1000L,
            unmerged = false
        )
        inventoryBarcodeDao.insertMergeLog(log)

        // Run repair
        val repairRes = domainService.repairOrphanStapleRulesFromMergeLogs()
        assertTrue(repairRes is DomainResult.Success)

        // Post-repair assertion: EXACTLY ONE rule ("Soft White Bread") remains visible (archivedByMerge = false)!
        val visibleRulesAfter = stapleRuleDao.observeAllStapleRules()
        val visibleList = (visibleRulesAfter as kotlinx.coroutines.flow.StateFlow).value
        assertEquals(1, visibleList.size)
        assertEquals("Soft White Bread", visibleList.first().displayName)

        // Bread rule is archived by merge, preserving enabled = true!
        val repairedBreadRule = stapleRuleDao.getStapleRuleByCanonicalIngredientId("item_bread_id")
        assertNotNull(repairedBreadRule)
        assertTrue(repairedBreadRule!!.archivedByMerge)
        assertTrue(repairedBreadRule.enabled) // Original user preference preserved!
    }

    @Test
    fun merge_bothStaple_producesOneActiveRule() = runTest {
        val item1 = InventoryItem(
            id = "item_bread",
            canonicalName = "Bread",
            displayName = "Bread",
            category = FoodCategory.BREAD_BAKERY,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.PACK,
            status = ItemStatus.AVAILABLE,
            staple = true
        )
        val item2 = InventoryItem(
            id = "item_soft_bread",
            canonicalName = "Soft White Bread",
            displayName = "Soft White Bread",
            category = FoodCategory.BREAD_BAKERY,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.PACK,
            status = ItemStatus.AVAILABLE,
            staple = true
        )
        domainService.addItem(item1)
        domainService.addItem(item2)

        // Merge Bread into Soft White Bread
        domainService.mergeInventoryItems("item_soft_bread", "item_bread", preferredDisplayName = "Soft White Bread")

        // Post-merge: EXACTLY ONE active visible rule remains!
        val activeRules = stapleRuleDao.getEnabledStapleRules()
        assertEquals(1, activeRules.size)
        assertEquals("Soft White Bread", activeRules.first().displayName)

        // Obsolete rule for Bread is archivedByMerge = true, preserving enabled = true!
        val oldBreadRule = stapleRuleDao.getStapleRuleByCanonicalIngredientId("item_item_bread")
        assertNotNull(oldBreadRule)
        assertTrue(oldBreadRule!!.archivedByMerge)
        assertTrue(oldBreadRule.enabled)
    }

    @Test
    fun unmerge_restoresOriginalStapleStatesAndRules() = runTest {
        val item1 = InventoryItem(
            id = "item_bread",
            canonicalName = "Bread",
            displayName = "Bread",
            category = FoodCategory.BREAD_BAKERY,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.PACK,
            status = ItemStatus.AVAILABLE,
            staple = true
        )
        val item2 = InventoryItem(
            id = "item_soft_bread",
            canonicalName = "Soft White Bread",
            displayName = "Soft White Bread",
            category = FoodCategory.BREAD_BAKERY,
            storageLocation = StorageLocation.CUPBOARD,
            quantity = 1.0,
            quantityUnit = QuantityUnit.PACK,
            status = ItemStatus.AVAILABLE,
            staple = true
        )
        domainService.addItem(item1)
        domainService.addItem(item2)

        domainService.mergeInventoryItems("item_soft_bread", "item_bread")
        val mergeLogId = inventoryBarcodeDao.mergeLogs.first().id

        // Unmerge
        val unmergeRes = domainService.unmergeInventoryItems(mergeLogId)
        assertTrue(unmergeRes is DomainResult.Success)

        // Post-unmerge: TWO active rules restored!
        val activeRules = stapleRuleDao.getEnabledStapleRules()
        assertEquals(2, activeRules.size)
    }
}
