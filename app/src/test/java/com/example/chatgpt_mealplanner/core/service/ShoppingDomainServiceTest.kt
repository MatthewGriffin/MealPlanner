package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealIngredientEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.StapleRuleMode
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
class ShoppingDomainServiceTest {

    private lateinit var shoppingDao: FakeShoppingDao
    private lateinit var mealDao: FakeMealDao
    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var runner: FakeTransactionRunner
    private lateinit var service: ShoppingDomainServiceImpl

    @Before
    fun setup() {
        shoppingDao = FakeShoppingDao()
        mealDao = FakeMealDao()
        inventoryDao = FakeInventoryDao()
        stapleRuleDao = FakeStapleRuleDao()
        transactionDao = FakeTransactionDao()
        runner = FakeTransactionRunner(
            fakeMealDao = mealDao,
            fakeInventoryDao = inventoryDao,
            fakeTransactionDao = transactionDao,
            fakeShoppingDao = shoppingDao,
            fakeStapleRuleDao = stapleRuleDao
        )

        service = ShoppingDomainServiceImpl(
            shoppingDao = shoppingDao,
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )
    }

    @Test
    fun staple_abovePackThreshold_doesNotAddShoppingRequirement() = runTest {
        // Inventory = 5 packs. Rule threshold = 1 pack, target = 4 packs.
        val lot = createLotWithPacks("lot_bread", "item_bread_id", "Soft White Bread", 5.0, QuantityUnit.PACK, 5)
        inventoryDao.insertItem(lot)

        val rule = StapleRuleEntity(
            id = "rule_bread",
            canonicalIngredientId = "item_bread_id",
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
        stapleRuleDao.insertOrUpdateStapleRule(rule)

        val res = service.calculateShoppingRequirements(null, null)
        assertTrue(res is DomainResult.Success)
        val items = (res as DomainResult.Success).value

        // Known stock is 5 packs > threshold 1 pack -> 0 requirement generated!
        assertEquals(0, items.size)
    }

    @Test
    fun staple_belowPackThreshold_addsOnlyTargetDeficit() = runTest {
        // Inventory = 1 pack. Rule threshold = 1 pack, target = 4 packs.
        val lot = createLotWithPacks("lot_bread", "item_bread_id", "Soft White Bread", 1.0, QuantityUnit.PACK, 1)
        inventoryDao.insertItem(lot)

        val rule = StapleRuleEntity(
            id = "rule_bread",
            canonicalIngredientId = "item_bread_id",
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
        stapleRuleDao.insertOrUpdateStapleRule(rule)

        val res = service.calculateShoppingRequirements(null, null)
        assertTrue(res is DomainResult.Success)
        val items = (res as DomainResult.Success).value

        // Known stock 1 <= threshold 1 -> target 4 - known 1 = 3 packs required!
        assertEquals(1, items.size)
        val item = items.first().item
        assertEquals(3.0, item.suggestedPurchaseQuantity!!, 0.01)
    }

    @Test
    fun staple_stockIncrease_removesGeneratedRequirement() = runTest {
        val lot = createLotWithPacks("lot_bread", "item_bread_id", "Soft White Bread", 1.0, QuantityUnit.PACK, 1)
        inventoryDao.insertItem(lot)

        val rule = StapleRuleEntity(
            id = "rule_bread",
            canonicalIngredientId = "item_bread_id",
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
        stapleRuleDao.insertOrUpdateStapleRule(rule)

        service.generateOrRegenerateShoppingList()
        val activeList1 = shoppingDao.getShoppingListWithItems("active_list") ?: shoppingDao.getShoppingListWithItems(shoppingDao.listsMap.keys.first())
        assertEquals(1, activeList1!!.items.size)

        // Restock inventory to 5 packs
        inventoryDao.insertItem(lot.copy(quantity = 5.0, packCount = 5))

        // Regenerate shopping list
        service.generateOrRegenerateShoppingList()
        val activeList2 = shoppingDao.getShoppingListWithItems(shoppingDao.listsMap.keys.first())

        // Generated staple requirement automatically removed!
        assertEquals(0, activeList2!!.items.size)
    }

    @Test
    fun staple_aboveQuantityThreshold_doesNotAddRequirement() = runTest {
        // Inventory = 750g. Rule threshold = 200g, target = 500g.
        val lot = createLot("lot_rice", "item_rice_id", "Rice", 750.0, QuantityUnit.GRAM)
        inventoryDao.insertItem(lot)

        val rule = StapleRuleEntity(
            id = "rule_rice",
            canonicalIngredientId = "item_rice_id",
            displayName = "Rice",
            enabled = true,
            archivedByMerge = false,
            mode = StapleRuleMode.QUANTITY,
            thresholdQuantity = 200.0,
            thresholdUnit = QuantityUnit.GRAM,
            targetQuantity = 500.0,
            targetUnit = QuantityUnit.GRAM,
            thresholdPackCount = null,
            targetPackCount = null,
            preferredStorageLocation = StorageLocation.CUPBOARD,
            notes = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        stapleRuleDao.insertOrUpdateStapleRule(rule)

        val res = service.calculateShoppingRequirements(null, null)
        assertTrue(res is DomainResult.Success)
        val items = (res as DomainResult.Success).value

        // Known stock 750g > threshold 200g -> 0 requirement!
        assertEquals(0, items.size)
    }

    @Test
    fun disabledStaple_doesNotGenerateShoppingRequirement() = runTest {
        val lot = createLotWithPacks("lot_bread", "item_bread_id", "Soft White Bread", 0.0, QuantityUnit.PACK, 0)
        inventoryDao.insertItem(lot)

        val rule = StapleRuleEntity(
            id = "rule_bread",
            canonicalIngredientId = "item_bread_id",
            displayName = "Soft White Bread",
            enabled = false, // DISABLED BY USER
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
        stapleRuleDao.insertOrUpdateStapleRule(rule)

        val res = service.calculateShoppingRequirements(null, null)
        assertTrue(res is DomainResult.Success)
        assertEquals(0, (res as DomainResult.Success).value.size)
    }

    @Test
    fun archivedMergedStaple_doesNotGenerateShoppingRequirement() = runTest {
        val lot = createLotWithPacks("lot_bread", "item_bread_id", "Soft White Bread", 0.0, QuantityUnit.PACK, 0)
        inventoryDao.insertItem(lot)

        val rule = StapleRuleEntity(
            id = "rule_bread",
            canonicalIngredientId = "item_bread_id",
            displayName = "Bread",
            enabled = true,
            archivedByMerge = true, // MERGED ARCHIVED RULE
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
        stapleRuleDao.insertOrUpdateStapleRule(rule)

        val res = service.calculateShoppingRequirements(null, null)
        assertTrue(res is DomainResult.Success)
        assertEquals(0, (res as DomainResult.Success).value.size)
    }

    @Test
    fun manualShoppingItem_isNotDeletedByStapleReconciliation() = runTest {
        // Active list created
        service.generateOrRegenerateShoppingList()
        val listId = shoppingDao.listsMap.keys.first()

        // User manually adds "Milk"
        service.addManualShoppingItem(listId, "Fresh Milk", 2.0, QuantityUnit.LITRE, "Manual note")

        // Regenerate shopping list
        service.generateOrRegenerateShoppingList()

        val listWithItems = shoppingDao.getShoppingListWithItems(listId)
        assertNotNull(listWithItems)
        val manualItems = listWithItems!!.items.filter { it.item.sourceType == ShoppingSourceType.MANUAL }
        assertEquals(1, manualItems.size)
        assertEquals("Fresh Milk", manualItems.first().item.displayName)
    }

    @Test
    fun unresolvedSameNamedIngredientsAcrossMeals_areCombinedIntoOneShoppingRequirement() = runTest {
        val firstMeal = createMealEntity("meal_garlic_1", "2099-01-01", MealStatus.PLANNED)
        val secondMeal = createMealEntity("meal_garlic_2", "2099-01-02", MealStatus.PLANNED)
        mealDao.insertMeal(firstMeal)
        mealDao.insertMeal(secondMeal)
        mealDao.insertMealIngredients(
            listOf(
                createMealIngredientEntity("ingredient_garlic_1", firstMeal.id, "", "Garlic cloves", 2.0, QuantityUnit.UNIT)
                    .copy(canonicalIngredientId = null),
                createMealIngredientEntity("ingredient_garlic_2", secondMeal.id, "", " garlic   cloves ", 3.0, QuantityUnit.UNIT)
                    .copy(canonicalIngredientId = null)
            )
        )

        val result = service.calculateShoppingRequirements(null, null)
        assertTrue(result is DomainResult.Success)
        val items = (result as DomainResult.Success).value

        assertEquals("Repeated unresolved ingredient names should produce one line", 1, items.size)
        assertEquals(5.0, items.single().item.requiredQuantity!!, 0.001)
        assertEquals(2, items.single().sources.size)
    }

    private fun createLot(id: String, canonicalId: String, name: String, qty: Double, unit: QuantityUnit) = InventoryItemEntity(
        id = id,
        barcode = null,
        canonicalIngredientId = canonicalId,
        canonicalName = name,
        displayName = name,
        brand = null,
        category = FoodCategory.FRIDGE,
        storageLocation = StorageLocation.FRIDGE,
        quantity = qty,
        quantityUnit = unit,
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

    private fun createLotWithPacks(id: String, canonicalId: String, name: String, qty: Double, unit: QuantityUnit, packCount: Int) = InventoryItemEntity(
        id = id,
        barcode = null,
        canonicalIngredientId = canonicalId,
        canonicalName = name,
        displayName = name,
        brand = null,
        category = FoodCategory.CUPBOARD,
        storageLocation = StorageLocation.CUPBOARD,
        quantity = qty,
        quantityUnit = unit,
        packCount = packCount,
        packSize = null,
        packSizeUnit = null,
        quantityConfidence = QuantityConfidence.EXACT,
        status = ItemStatus.AVAILABLE,
        opened = false,
        frozen = false,
        staple = true,
        purchaseDate = null,
        openedDate = null,
        useByDate = null,
        bestBeforeDate = null,
        notes = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun createMealEntity(id: String, date: String, status: MealStatus) = MealEntity(
        id = id,
        date = date,
        mealType = MealType.DINNER,
        title = "Planned Meal $id",
        servings = 2,
        status = status,
        batchPortions = 2,
        immediatePortions = 2,
        leftoverPortions = 0,
        cookInTwoBatches = false,
        tags = emptySet(),
        instructions = null,
        notes = null,
        cookedAt = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun createMealIngredientEntity(id: String, mealId: String, canonicalId: String, name: String, qty: Double, unit: QuantityUnit) = MealIngredientEntity(
        id = id,
        mealId = mealId,
        canonicalIngredientId = canonicalId,
        displayName = name,
        plannedQuantity = qty,
        plannedUnit = unit,
        actualQuantity = null,
        actualUnit = null,
        optional = false,
        substitutionAllowed = true,
        substitutedCanonicalIngredientId = null,
        substitutedDisplayName = null
    )
}
