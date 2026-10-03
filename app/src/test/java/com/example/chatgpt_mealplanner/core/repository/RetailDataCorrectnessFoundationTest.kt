package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.backend.retail.AsdaRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.IcelandRetailerAdapter
import com.example.chatgpt_mealplanner.core.database.entity.RetailPriceSnapshotEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListItem
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingPriority
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.WasteRisk
import com.example.chatgpt_mealplanner.core.service.FakeChatDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeLeftoverDao
import com.example.chatgpt_mealplanner.core.service.FakeMealDao
import com.example.chatgpt_mealplanner.core.service.FakeRetailDao
import com.example.chatgpt_mealplanner.core.service.FakeShoppingDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.ReceiptImportDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.RetailShoppingDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainServiceImpl
import com.example.chatgpt_mealplanner.feature.shopping.ShoppingViewModel
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.PriceFreshness
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailFreshnessPolicy
import com.example.chatgpt_mealplanner.retail.contract.RetailOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailOfferSourceType
import com.example.chatgpt_mealplanner.retail.contract.RetailProduct
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailStorageClass
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.engine.BasketOptimizer
import com.example.chatgpt_mealplanner.retail.engine.RetailProductMatcher
import com.example.chatgpt_mealplanner.retail.engine.ShoppingRequirementSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class RetailDataCorrectnessFoundationTest {

    private lateinit var retailDao: FakeRetailDao
    private lateinit var shoppingDao: FakeShoppingDao
    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var mealDao: FakeMealDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var leftoverDao: FakeLeftoverDao
    private lateinit var barcodeDao: FakeInventoryBarcodeDao

    private lateinit var retailRepository: RetailRepositoryImpl
    private lateinit var shoppingDomainService: ShoppingDomainServiceImpl
    private lateinit var retailShoppingDomainService: RetailShoppingDomainServiceImpl

    @Before
    fun setup() {
        retailDao = FakeRetailDao()
        shoppingDao = FakeShoppingDao()
        inventoryDao = FakeInventoryDao()
        mealDao = FakeMealDao()
        stapleRuleDao = FakeStapleRuleDao()
        transactionDao = FakeTransactionDao()
        leftoverDao = FakeLeftoverDao()
        barcodeDao = FakeInventoryBarcodeDao()

        val runner = FakeTransactionRunner(
            fakeMealDao = mealDao,
            fakeInventoryDao = inventoryDao,
            fakeTransactionDao = transactionDao,
            fakeLeftoverDao = leftoverDao,
            fakeShoppingDao = shoppingDao,
            fakeStapleRuleDao = stapleRuleDao
        )

        retailRepository = RetailRepositoryImpl(retailDao)

        shoppingDomainService = ShoppingDomainServiceImpl(
            shoppingDao = shoppingDao,
            mealDao = mealDao,
            inventoryDao = inventoryDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )

        val failingIcelandAdapter = object : IcelandRetailerAdapter() {
            override suspend fun searchProducts(query: String): RetailProductSearchResult {
                return RetailProductSearchResult.Unavailable(
                    reason = "Offline for unit test",
                    errorCode = "OFFLINE",
                    cachedFallbackAvailable = true
                )
            }
        }

        val failingAsdaAdapter = object : AsdaRetailerAdapter() {
            override suspend fun searchProducts(query: String): RetailProductSearchResult {
                return RetailProductSearchResult.Unavailable(
                    reason = "Offline for unit test",
                    errorCode = "OFFLINE",
                    cachedFallbackAvailable = false
                )
            }
        }

        retailShoppingDomainService = RetailShoppingDomainServiceImpl(
            shoppingDomainService = shoppingDomainService,
            shoppingDao = shoppingDao,
            retailRepository = retailRepository,
            retailDao = retailDao,
            icelandAdapter = failingIcelandAdapter,
            asdaAdapter = failingAsdaAdapter
        )
    }

    @Test
    fun emptyRetailDatabase_returnsNoFakeOffers() = runTest {
        val candidates = retailRepository.getCandidateProductsWithOffers()
        assertTrue("Empty RetailDao MUST return empty list from repository", candidates.isEmpty())
    }

    @Test
    fun emptyRetailDatabase_doesNotUseFixtures() = runTest {
        runBlocking {
            shoppingDao.insertShoppingList(
                ShoppingListEntity("list_1", "Active List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L)
            )
            shoppingDao.insertShoppingListItem(
                ShoppingListItemEntity(
                    id = "item_spaghetti",
                    shoppingListId = "list_1",
                    canonicalIngredientId = "spaghetti",
                    displayName = "Spaghetti",
                    requiredQuantity = 1.0,
                    requiredUnit = QuantityUnit.PACK,
                    suggestedPurchaseQuantity = 1.0,
                    suggestedPurchaseUnit = QuantityUnit.PACK,
                    packCount = 1,
                    reason = "Dinner",
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
        }

        val result = retailShoppingDomainService.optimizeShoppingBasket()
        assertTrue(result is DomainResult.Success)

        val comparison = (result as DomainResult.Success).value
        assertEquals("Basket subtotal MUST be £0.00 when database contains no retail offers", Money.zero(), comparison.selectedPlan.basketSubtotal)
        assertTrue("All requirements MUST be in unmatched list when database is empty", comparison.selectedPlan.unmatchedRequirements.contains("item_spaghetti"))
        assertTrue("No lines MUST be generated from fake fixtures", comparison.selectedPlan.lines.isEmpty())
    }

    @Test
    fun cachedLatestPrice_isReturnedByRepository() = runTest {
        runBlocking {
            retailDao.insertOrUpdateProduct(
                RetailProductEntity(
                    id = "ASDA_1001",
                    retailerId = RetailerId.ASDA,
                    retailerProductId = "1001",
                    name = "ASDA Smart Price Spaghetti 500g",
                    brand = "ASDA",
                    canonicalIngredientId = "spaghetti",
                    barcode = null,
                    category = "Pasta",
                    imageUrl = null,
                    packageType = null,
                    netQuantity = 500.0,
                    netQuantityUnit = "GRAM",
                    packCount = 1,
                    packSize = 500.0,
                    packSizeUnit = "GRAM",
                    storageClass = RetailStorageClass.AMBIENT,
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            )
            retailDao.insertPriceSnapshot(
                RetailPriceSnapshotEntity(
                    id = "snap_1",
                    retailerId = RetailerId.ASDA,
                    retailerProductId = "1001",
                    priceMinorPence = 85L,
                    currencyCode = "GBP",
                    promotionJson = null,
                    availability = RetailAvailability.IN_STOCK,
                    observedAt = 2000L
                )
            )
        }

        val candidates = retailRepository.getCandidateProductsWithOffers()
        assertEquals(1, candidates.size)
        assertEquals("ASDA Smart Price Spaghetti 500g", candidates.first().product.name)
        assertEquals(Money(85L), candidates.first().offer.price)
    }

    @Test
    fun historicalPriceSnapshots_onlyLatestApplicableOfferReturned() = runTest {
        runBlocking {
            retailDao.insertOrUpdateProduct(
                RetailProductEntity(
                    id = "ICELAND_2001",
                    retailerId = RetailerId.ICELAND,
                    retailerProductId = "2001",
                    name = "Iceland Frozen Peas 1kg",
                    brand = "Iceland",
                    canonicalIngredientId = "peas",
                    barcode = null,
                    category = "Frozen",
                    imageUrl = null,
                    packageType = null,
                    netQuantity = 1000.0,
                    netQuantityUnit = "GRAM",
                    packCount = 1,
                    packSize = 1000.0,
                    packSizeUnit = "GRAM",
                    storageClass = RetailStorageClass.FROZEN,
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            )

            // Older snapshot (1000L) @ £1.20
            retailDao.insertPriceSnapshot(
                RetailPriceSnapshotEntity("snap_old", RetailerId.ICELAND, "2001", 120L, "GBP", null, RetailAvailability.IN_STOCK, 1000L)
            )
            // Newer snapshot (5000L) @ £1.50
            retailDao.insertPriceSnapshot(
                RetailPriceSnapshotEntity("snap_new", RetailerId.ICELAND, "2001", 150L, "GBP", null, RetailAvailability.IN_STOCK, 5000L)
            )
        }

        val candidates = retailRepository.getCandidateProductsWithOffers()
        assertEquals(1, candidates.size)
        assertEquals(Money(150L), candidates.first().offer.price)
        assertEquals(5000L, candidates.first().offer.observedAt)
    }

    @Test
    fun receiptImportedPrice_isAvailableToBasketOptimizer() = runTest {
        runBlocking {
            retailDao.insertOrUpdateProduct(
                RetailProductEntity("ICELAND_RECEIPT_1", RetailerId.ICELAND, "RECEIPT_1", "Iceland Garlic Bread", "Iceland", "garlic_bread", null, null, null, null, null, null, 1, 1.0, "ITEM", RetailStorageClass.FROZEN, 1000L, 1000L)
            )
            retailDao.insertPriceSnapshot(
                RetailPriceSnapshotEntity("snap_rcpt", RetailerId.ICELAND, "RECEIPT_1", 125L, "GBP", null, RetailAvailability.IN_STOCK, System.currentTimeMillis())
            )

            shoppingDao.insertShoppingList(ShoppingListEntity("list_rcpt", "List", null, null, ShoppingListStatus.ACTIVE, 1000L, 1000L))
            shoppingDao.insertShoppingListItem(
                ShoppingListItemEntity("item_gb", "list_rcpt", "garlic_bread", "Garlic Bread", 1.0, QuantityUnit.PACK, 1.0, QuantityUnit.PACK, 1, "Meal", ShoppingPriority.ESSENTIAL, ShoppingItemStatus.NEEDED, false, ShoppingSourceType.MEAL_REQUIREMENT, true, false, null, null, null, null, false, null, RequirementConfidence.EXACT, WasteRisk.LOW, 1000L, 1000L)
            )
        }

        val result = retailShoppingDomainService.optimizeShoppingBasket()
        assertTrue(result is DomainResult.Success)
        val plan = (result as DomainResult.Success).value.selectedPlan
        assertEquals(Money(125L), plan.basketSubtotal)
        assertEquals(1, plan.lines.size)
        assertEquals("Iceland Garlic Bread", plan.lines.first().productWithOffer.product.name)
    }

    @Test
    fun staleCachedPrice_preservesStaleFreshness() = runTest {
        val oldTimestamp = System.currentTimeMillis() - (48 * 60 * 60 * 1000L) // 48h ago
        val offer = RetailOffer(
            retailerId = RetailerId.ASDA,
            retailerProductId = "stale_1",
            price = Money(100L),
            observedAt = oldTimestamp
        )

        val freshness = offer.calculateFreshness(RetailFreshnessPolicy())
        assertEquals(PriceFreshness.STALE, freshness)
    }

    @Test
    fun retailerIdentity_preservedFromCachedOffer() = runTest {
        runBlocking {
            retailDao.insertOrUpdateProduct(
                RetailProductEntity("ASDA_P1", RetailerId.ASDA, "P1", "ASDA Milk", "ASDA", "milk", null, null, null, null, null, null, 1, 1.0, "ITEM", RetailStorageClass.CHILLED, 1000L, 1000L)
            )
            retailDao.insertPriceSnapshot(
                RetailPriceSnapshotEntity("snap_milk", RetailerId.ASDA, "P1", 130L, "GBP", null, RetailAvailability.IN_STOCK, System.currentTimeMillis())
            )
        }

        val candidates = retailRepository.getCandidateProductsWithOffers()
        assertEquals(1, candidates.size)
        assertEquals(RetailerId.ASDA, candidates.first().product.retailerId)
        assertEquals(RetailerId.ASDA, candidates.first().offer.retailerId)
    }

    @Test
    fun duplicateDisplayNames_useStableRequirementIds() = runTest {
        val spec1 = ShoppingRequirementSpec("id_req_1", "milk", "Milk", 1.0, "PACK", "ESSENTIAL")
        val spec2 = ShoppingRequirementSpec("id_req_2", "milk", "Milk", 1.0, "PACK", "ESSENTIAL")

        val optimizer = BasketOptimizer(RetailProductMatcher(emptyMap()))
        val result = optimizer.optimizeBasket(listOf(spec1, spec2), emptyList())

        val unmatched = result.selectedPlan.unmatchedRequirements
        assertEquals(2, unmatched.size)
        assertTrue("unmatchedRequirements MUST contain stable requirement IDs, not display names", unmatched.contains("id_req_1"))
        assertTrue("unmatchedRequirements MUST contain stable requirement IDs, not display names", unmatched.contains("id_req_2"))
    }

    @Test
    fun unmatchedRequirements_useStableIdentity() = runTest {
        val spec = ShoppingRequirementSpec("stable_id_999", "cheese", "Cheddar Cheese", 1.0, "PACK", "ESSENTIAL")
        val optimizer = BasketOptimizer(RetailProductMatcher(emptyMap()))
        val result = optimizer.optimizeBasket(listOf(spec), emptyList())

        assertEquals(listOf("stable_id_999"), result.selectedPlan.unmatchedRequirements)
    }

    @Test
    fun retailNetworkFailure_preservesCachedOffers() = runTest {
        runBlocking {
            retailDao.insertOrUpdateProduct(
                RetailProductEntity("ICELAND_P100", RetailerId.ICELAND, "P100", "Iceland Pizza", "Iceland", "pizza", null, null, null, null, null, null, 1, 1.0, "ITEM", RetailStorageClass.FROZEN, 1000L, 1000L)
            )
            retailDao.insertPriceSnapshot(
                RetailPriceSnapshotEntity("snap_pizza", RetailerId.ICELAND, "P100", 250L, "GBP", null, RetailAvailability.IN_STOCK, System.currentTimeMillis())
            )
        }

        // Domain service with failing network adapter
        val result = retailShoppingDomainService.searchProducts("pizza")
        assertTrue(result is DomainResult.Success)
        val matches = (result as DomainResult.Success).value
        assertEquals(1, matches.size)
        assertEquals("Iceland Pizza", matches.first().product.name)
    }

    @Test
    fun retailNetworkFailure_neverFallsBackToFixtures() = runTest {
        // Database is empty, network adapter fails
        val result = retailShoppingDomainService.searchProducts("non_existent_item")
        assertTrue(result is DomainResult.Success)
        val matches = (result as DomainResult.Success).value
        assertTrue("Network failure MUST return empty list when database is empty, NEVER fallback to fixtures", matches.isEmpty())
    }

    @Test
    fun foodWarehouse_doesNotReceiveSyntheticIcelandPrice() = runTest {
        runBlocking {
            retailDao.insertOrUpdateProduct(
                RetailProductEntity("ICELAND_P1", RetailerId.ICELAND, "P1", "Iceland Chips", "Iceland", "chips", null, null, null, null, null, null, 1, 1.0, "ITEM", RetailStorageClass.FROZEN, 1000L, 1000L)
            )
            retailDao.insertPriceSnapshot(
                RetailPriceSnapshotEntity("snap_chips", RetailerId.ICELAND, "P1", 150L, "GBP", null, RetailAvailability.IN_STOCK, System.currentTimeMillis())
            )
        }

        val candidates = retailRepository.getCandidateProductsWithOffers()
        assertTrue("No synthetic Food Warehouse offers must be created for Iceland products", candidates.none { it.product.retailerId == RetailerId.FOOD_WAREHOUSE })
    }

    @Test
    fun aiRetailSearch_noRealOffers_doesNotClaimCheapestFixture() = runTest {
        val result = retailShoppingDomainService.searchProducts("dishwasher tablets")
        assertTrue(result is DomainResult.Success)
        val matches = (result as DomainResult.Success).value
        assertTrue("AI Retail search MUST return empty list when no real offers exist", matches.isEmpty())
    }

    @Test
    fun icelandSearch_genuineResult_isCached() = runTest {
        val mockIcelandAdapter = object : IcelandRetailerAdapter() {
            override suspend fun searchProducts(query: String): RetailProductSearchResult {
                val prod = RetailProduct(
                    retailerId = RetailerId.ICELAND,
                    retailerProductId = "live_iceland_1",
                    name = "Iceland Live Baked Beans 415g",
                    canonicalIngredientId = "beans"
                )
                val offer = RetailOffer(
                    retailerId = RetailerId.ICELAND,
                    retailerProductId = "live_iceland_1",
                    price = Money(50L),
                    sourceType = RetailOfferSourceType.LIVE
                )
                return RetailProductSearchResult.Success(
                    productsWithOffers = listOf(RetailProductWithOffer(prod, offer)),
                    metadata = com.example.chatgpt_mealplanner.retail.contract.RetailSourceMetadata(
                        retailerId = RetailerId.ICELAND,
                        sourceReference = "live_test"
                    )
                )
            }
        }

        val service = RetailShoppingDomainServiceImpl(
            shoppingDomainService = shoppingDomainService,
            shoppingDao = shoppingDao,
            retailRepository = retailRepository,
            retailDao = retailDao,
            icelandAdapter = mockIcelandAdapter,
            asdaAdapter = object : AsdaRetailerAdapter() {
                override suspend fun searchProducts(query: String): RetailProductSearchResult {
                    return RetailProductSearchResult.Unavailable("Offline", "OFFLINE", true)
                }
            }
        )

        val searchRes = service.searchProducts("baked beans")
        assertTrue(searchRes is DomainResult.Success)
        val matches = (searchRes as DomainResult.Success).value
        assertEquals(1, matches.size)
        assertEquals("Iceland Live Baked Beans 415g", matches.first().product.name)

        // Verify cached in RetailDao
        val cached = retailRepository.getCandidateProductsWithOffers()
        assertEquals(1, cached.size)
        assertEquals("Iceland Live Baked Beans 415g", cached.first().product.name)
    }
}
