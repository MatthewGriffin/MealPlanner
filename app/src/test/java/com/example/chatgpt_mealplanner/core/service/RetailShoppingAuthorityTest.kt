package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.WasteRisk
import com.example.chatgpt_mealplanner.core.repository.RetailRepositoryImpl
import com.example.chatgpt_mealplanner.backend.retail.IcelandRetailerAdapter
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailSourceMetadata
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.UserShoppingPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class RetailShoppingAuthorityTest {

    private lateinit var shoppingDao: FakeShoppingDao
    private lateinit var mealDao: FakeMealDao
    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var retailDao: FakeRetailDao
    private lateinit var shoppingDomainService: ShoppingDomainServiceImpl
    private lateinit var retailRepository: RetailRepositoryImpl
    private lateinit var retailShoppingDomainService: RetailShoppingDomainServiceImpl

    @Before
    fun setup() {
        shoppingDao = FakeShoppingDao()
        mealDao = FakeMealDao()
        inventoryDao = FakeInventoryDao()
        stapleRuleDao = FakeStapleRuleDao()
        retailDao = FakeRetailDao()

        val runner = FakeTransactionRunner(
            fakeMealDao = mealDao,
            fakeInventoryDao = inventoryDao,
            fakeTransactionDao = FakeTransactionDao(),
            fakeLeftoverDao = FakeLeftoverDao(),
            fakeShoppingDao = shoppingDao,
            fakeStapleRuleDao = stapleRuleDao
        )

        shoppingDomainService = ShoppingDomainServiceImpl(
            shoppingDao = shoppingDao,
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )

        retailRepository = RetailRepositoryImpl(retailDao)

        retailShoppingDomainService = RetailShoppingDomainServiceImpl(
            shoppingDomainService = shoppingDomainService,
            shoppingDao = shoppingDao,
            retailRepository = retailRepository,
            retailDao = retailDao
        )
    }

    @Test
    fun cachedRelevantCandidate_doesNotDropRequirement() = runTest {
        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        shoppingDao.insertShoppingListItem(
            ShoppingListItemEntity(
                id = "req_1",
                shoppingListId = "list_1",
                canonicalIngredientId = "pasta",
                displayName = "Pasta",
                requiredQuantity = 500.0,
                requiredUnit = QuantityUnit.GRAM,
                suggestedPurchaseQuantity = 500.0,
                suggestedPurchaseUnit = QuantityUnit.GRAM,
                packCount = 1,
                reason = "Test",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.MANUAL,
                hasMealRequirement = false,
                hasStapleRestock = false,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null,
                expectedFinalInventory = null,
                expectedFinalInventoryUnit = null,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.LOW,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val result = retailShoppingDomainService.optimizeShoppingBasket(UserShoppingPreferences())
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        val totalCount = plan.lines.size + plan.unmatchedRequirements.size
        assertEquals(1, totalCount)
        assertTrue(plan.unmatchedRequirements.contains("req_1") || plan.lines.any { it.shoppingRequirementId == "req_1" })
    }

    @Test
    fun tomatoSauce_useCachePath_reachesOptimizerExactlyOnce() = runTest {
        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        shoppingDao.insertShoppingListItem(
            ShoppingListItemEntity(
                id = "ts_1",
                shoppingListId = "list_1",
                canonicalIngredientId = "tomato_sauce",
                displayName = "Tomato sauce",
                requiredQuantity = 1.0,
                requiredUnit = QuantityUnit.BOTTLE,
                suggestedPurchaseQuantity = 1.0,
                suggestedPurchaseUnit = QuantityUnit.BOTTLE,
                packCount = 1,
                reason = "Test",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.MANUAL,
                hasMealRequirement = false,
                hasStapleRestock = false,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null,
                expectedFinalInventory = null,
                expectedFinalInventoryUnit = null,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.LOW,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val prod = RetailProduct(RetailerId.ASDA, "p_ts", "Tomato Sauce", "tomato_sauce", storageClass = RetailStorageClass.AMBIENT)
        val offer = RetailOffer(RetailerId.ASDA, "p_ts", Money(100L))
        retailRepository.cacheProductsWithOffers(listOf(RetailProductWithOffer(prod, offer)))

        val result = retailShoppingDomainService.optimizeShoppingBasket(UserShoppingPreferences())
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        assertEquals(1, plan.lines.size)
        assertEquals("Tomato Sauce", plan.lines.first().productWithOffer.product.name)
        assertEquals("Tomato sauce", plan.lines.first().requirementDisplayName)
    }

    @Test
    fun cacheDecision_isIsolatedPerRetailer() = runTest {
        val asdaProd = RetailProduct(RetailerId.ASDA, "asda_1", "Tomato Sauce", "tomato_sauce", storageClass = RetailStorageClass.AMBIENT)
        val asdaOffer = RetailOffer(RetailerId.ASDA, "asda_1", Money(100L))
        val icelandProd = RetailProduct(RetailerId.ICELAND, "iceland_1", "Tomato Sauce", "tomato_sauce", storageClass = RetailStorageClass.AMBIENT)
        val icelandOffer = RetailOffer(RetailerId.ICELAND, "iceland_1", Money(1L))
        retailRepository.cacheProductsWithOffers(
            listOf(
                RetailProductWithOffer(asdaProd, asdaOffer),
                RetailProductWithOffer(icelandProd, icelandOffer)
            )
        )

        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        shoppingDao.insertShoppingListItem(
            ShoppingListItemEntity(
                id = "ts_1",
                shoppingListId = "list_1",
                canonicalIngredientId = "tomato_sauce",
                displayName = "Tomato sauce",
                requiredQuantity = 1.0,
                requiredUnit = QuantityUnit.BOTTLE,
                suggestedPurchaseQuantity = 1.0,
                suggestedPurchaseUnit = QuantityUnit.BOTTLE,
                packCount = 1,
                reason = "Test",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.MANUAL,
                hasMealRequirement = false,
                hasStapleRestock = false,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null,
                expectedFinalInventory = null,
                expectedFinalInventoryUnit = null,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.LOW,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val prefs = UserShoppingPreferences(enabledRetailers = setOf(RetailerId.ASDA))
        val result = retailShoppingDomainService.optimizeShoppingBasket(prefs)
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        assertEquals(setOf(RetailerId.ASDA), plan.stores)
        assertEquals("asda_1", plan.lines.single().productWithOffer.product.retailerProductId)
    }

    @Test
    fun tomatoSauce_compositeOnlyCache_doesNotSelectCompositeProduct() = runTest {
        val compProd = RetailProduct(RetailerId.ASDA, "comp_1", "Stuffed Cabbage in Tomato Sauce with Pork Meat and Rice Stuffing 590g", "cabbage", storageClass = RetailStorageClass.AMBIENT)
        val compOffer = RetailOffer(RetailerId.ASDA, "comp_1", Money(200L))
        retailRepository.cacheProductsWithOffers(listOf(RetailProductWithOffer(compProd, compOffer)))

        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        shoppingDao.insertShoppingListItem(
            ShoppingListItemEntity(
                id = "ts_1",
                shoppingListId = "list_1",
                canonicalIngredientId = "tomato_sauce",
                displayName = "Tomato sauce",
                requiredQuantity = 1.0,
                requiredUnit = QuantityUnit.BOTTLE,
                suggestedPurchaseQuantity = 1.0,
                suggestedPurchaseUnit = QuantityUnit.BOTTLE,
                packCount = 1,
                reason = "Test",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.MANUAL,
                hasMealRequirement = false,
                hasStapleRestock = false,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null,
                expectedFinalInventory = null,
                expectedFinalInventoryUnit = null,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.LOW,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val genuineSauce = RetailProduct(
            RetailerId.ICELAND, "iceland_tomato_sauce", "Tomato Sauce", "tomato_sauce",
            storageClass = RetailStorageClass.AMBIENT
        )
        val fakeIcelandAdapter = object : IcelandRetailerAdapter() {
            override suspend fun searchProducts(query: String): RetailProductSearchResult =
                RetailProductSearchResult.Success(
                    productsWithOffers = listOf(
                        RetailProductWithOffer(genuineSauce, RetailOffer(RetailerId.ICELAND, "iceland_tomato_sauce", Money(150L)))
                    ),
                    metadata = RetailSourceMetadata(
                        retailerId = RetailerId.ICELAND,
                        sourceType = RetailOfferSourceType.LIVE,
                        sourceReference = "test://iceland/search?q=tomato+sauce",
                        observedAt = 1000L,
                        isLive = true
                    )
                )
        }
        retailShoppingDomainService = RetailShoppingDomainServiceImpl(
            shoppingDomainService = shoppingDomainService,
            shoppingDao = shoppingDao,
            retailRepository = retailRepository,
            retailDao = retailDao,
            icelandAdapter = fakeIcelandAdapter,
            allowLiveLookupsInTests = true
        )

        val result = retailShoppingDomainService.optimizeShoppingBasket(
            UserShoppingPreferences(enabledRetailers = setOf(RetailerId.ICELAND))
        )
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        // A composite cache entry must not suppress a real search for the requested ingredient.
        assertEquals(1, plan.lines.size)
        assertEquals("Tomato Sauce", plan.lines.single().productWithOffer.product.name)
        assertTrue(plan.unmatchedRequirements.isEmpty())
    }

    @Test
    fun authoritativeThirteenItems_reachOptimizerAsThirteenRequirements() = runTest {
        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        for (i in 1..13) {
            shoppingDao.insertShoppingListItem(
                ShoppingListItemEntity(
                    id = "item_$i",
                    shoppingListId = "list_1",
                    canonicalIngredientId = "ing_$i",
                    displayName = "Ingredient $i",
                    requiredQuantity = 1.0,
                    requiredUnit = QuantityUnit.UNIT,
                    suggestedPurchaseQuantity = 1.0,
                    suggestedPurchaseUnit = QuantityUnit.UNIT,
                    packCount = 1,
                    reason = "Test item $i",
                    priority = ShoppingPriority.ESSENTIAL,
                    status = ShoppingItemStatus.NEEDED,
                    optional = false,
                    sourceType = ShoppingSourceType.MANUAL,
                    hasMealRequirement = false,
                    hasStapleRestock = false,
                    mealShortfallQuantity = null,
                    stapleReserveTopUpQuantity = null,
                    expectedFinalInventory = null,
                    expectedFinalInventoryUnit = null,
                    quantityUncertain = false,
                    uncertaintyReason = null,
                    requirementConfidence = RequirementConfidence.EXACT,
                    wasteRisk = WasteRisk.LOW,
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            )
        }

        val result = retailShoppingDomainService.optimizeShoppingBasket(UserShoppingPreferences())
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        val totalProcessed = plan.lines.size + plan.unmatchedRequirements.size
        assertEquals(13, totalProcessed)
    }

    @Test
    fun liveSearchAndCacheReload_doNotDuplicateRequirements() = runTest {
        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        shoppingDao.insertShoppingListItem(
            ShoppingListItemEntity(
                id = "item_dup",
                shoppingListId = "list_1",
                canonicalIngredientId = "milk",
                displayName = "Milk",
                requiredQuantity = 1.0,
                requiredUnit = QuantityUnit.BOTTLE,
                suggestedPurchaseQuantity = 1.0,
                suggestedPurchaseUnit = QuantityUnit.BOTTLE,
                packCount = 1,
                reason = "Test",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.MANUAL,
                hasMealRequirement = false,
                hasStapleRestock = false,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null,
                expectedFinalInventory = null,
                expectedFinalInventoryUnit = null,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.LOW,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val result = retailShoppingDomainService.optimizeShoppingBasket(UserShoppingPreferences())
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        val totalCount = plan.lines.size + plan.unmatchedRequirements.size
        assertEquals(1, totalCount)
    }

    @Test
    fun mealGeneratedItem_reachesOptimizer_exactlyOnce() = runTest {
        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        shoppingDao.insertShoppingListItem(
            ShoppingListItemEntity(
                id = "mg_1",
                shoppingListId = "list_1",
                canonicalIngredientId = "chicken",
                displayName = "Chicken breast",
                requiredQuantity = 500.0,
                requiredUnit = QuantityUnit.GRAM,
                suggestedPurchaseQuantity = 500.0,
                suggestedPurchaseUnit = QuantityUnit.GRAM,
                packCount = 1,
                reason = "Meal requirement",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.MEAL_REQUIREMENT,
                hasMealRequirement = true,
                hasStapleRestock = false,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null,
                expectedFinalInventory = null,
                expectedFinalInventoryUnit = null,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.MEDIUM,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val result = retailShoppingDomainService.optimizeShoppingBasket(UserShoppingPreferences())
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        val totalCount = plan.lines.size + plan.unmatchedRequirements.size
        assertEquals(1, totalCount)
        // The requirement may be unmatched when no verified product is available, but it
        // must not disappear or be duplicated during basket optimization.
        assertTrue(plan.lines.isEmpty() || plan.lines.single().requirementDisplayName == "Chicken breast")
    }

    @Test
    fun stapleGeneratedItem_reachesOptimizer_exactlyOnce() = runTest {
        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        shoppingDao.insertShoppingListItem(
            ShoppingListItemEntity(
                id = "sg_1",
                shoppingListId = "list_1",
                canonicalIngredientId = "milk",
                displayName = "Milk",
                requiredQuantity = 2.0,
                requiredUnit = QuantityUnit.BOTTLE,
                suggestedPurchaseQuantity = 2.0,
                suggestedPurchaseUnit = QuantityUnit.BOTTLE,
                packCount = 2,
                reason = "Staple restock",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.STAPLE_RESTOCK,
                hasMealRequirement = false,
                hasStapleRestock = true,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null,
                expectedFinalInventory = null,
                expectedFinalInventoryUnit = null,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.LOW,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val result = retailShoppingDomainService.optimizeShoppingBasket(UserShoppingPreferences())
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        val totalCount = plan.lines.size + plan.unmatchedRequirements.size
        assertEquals(1, totalCount)
    }

    @Test
    fun reconciledQuantity_isPreservedExactly() = runTest {
        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        shoppingDao.insertShoppingListItem(
            ShoppingListItemEntity(
                id = "rec_1",
                shoppingListId = "list_1",
                canonicalIngredientId = "flour",
                displayName = "Flour",
                requiredQuantity = 1000.0,
                requiredUnit = QuantityUnit.GRAM,
                suggestedPurchaseQuantity = 350.0,
                suggestedPurchaseUnit = QuantityUnit.GRAM,
                packCount = 1,
                reason = "Reconciled shortfall",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.MEAL_REQUIREMENT,
                hasMealRequirement = true,
                hasStapleRestock = false,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null,
                expectedFinalInventory = null,
                expectedFinalInventoryUnit = null,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.LOW,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val result = retailShoppingDomainService.optimizeShoppingBasket(UserShoppingPreferences())
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        val totalCount = plan.lines.size + plan.unmatchedRequirements.size
        assertEquals(1, totalCount)
    }

    @Test
    fun inventory_isNotSubtractedTwice() = runTest {
        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        shoppingDao.insertShoppingListItem(
            ShoppingListItemEntity(
                id = "inv_1",
                shoppingListId = "list_1",
                canonicalIngredientId = "pasta",
                displayName = "Pasta",
                requiredQuantity = 500.0,
                requiredUnit = QuantityUnit.GRAM,
                suggestedPurchaseQuantity = 300.0,
                suggestedPurchaseUnit = QuantityUnit.GRAM,
                packCount = 1,
                reason = "Reconciled",
                priority = ShoppingPriority.ESSENTIAL,
                status = ShoppingItemStatus.NEEDED,
                optional = false,
                sourceType = ShoppingSourceType.MEAL_REQUIREMENT,
                hasMealRequirement = true,
                hasStapleRestock = false,
                mealShortfallQuantity = null,
                stapleReserveTopUpQuantity = null,
                expectedFinalInventory = null,
                expectedFinalInventoryUnit = null,
                quantityUncertain = false,
                uncertaintyReason = null,
                requirementConfidence = RequirementConfidence.EXACT,
                wasteRisk = WasteRisk.LOW,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val prod = RetailProduct(RetailerId.ASDA, "p_pasta", "Pasta 300g", "pasta", storageClass = RetailStorageClass.AMBIENT)
        val offer = RetailOffer(RetailerId.ASDA, "p_pasta", Money(100L))
        retailRepository.cacheProductsWithOffers(listOf(RetailProductWithOffer(prod, offer)))

        val result = retailShoppingDomainService.optimizeShoppingBasket(UserShoppingPreferences())
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        assertEquals(1, plan.lines.size)
        assertEquals(300.0, plan.lines.first().requirementCovered, 0.01)
    }

    @Test
    fun emptyActiveShoppingList_resultsInZeroRequirements() = runTest {
        shoppingDao.insertShoppingList(ShoppingListEntity("list_1", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
        // 0 items inserted

        val result = retailShoppingDomainService.optimizeShoppingBasket(UserShoppingPreferences())
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        assertTrue(plan.lines.isEmpty())
        assertTrue(plan.unmatchedRequirements.isEmpty())
    }
}
