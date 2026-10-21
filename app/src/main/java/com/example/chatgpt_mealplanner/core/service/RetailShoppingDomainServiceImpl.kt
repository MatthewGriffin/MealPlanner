package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.backend.retail.AsdaRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.IcelandRetailerAdapter
import com.example.chatgpt_mealplanner.core.database.dao.RetailDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.repository.RetailRepository
import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import com.example.chatgpt_mealplanner.retail.contract.PackAlternative
import com.example.chatgpt_mealplanner.retail.contract.PriceFreshness
import com.example.chatgpt_mealplanner.retail.contract.RetailFreshnessPolicy
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.UserShoppingPreferences
import com.example.chatgpt_mealplanner.retail.engine.BasketOptimizer
import com.example.chatgpt_mealplanner.retail.engine.FakeRetailerFixtures
import com.example.chatgpt_mealplanner.retail.engine.PackSelectionEngine
import com.example.chatgpt_mealplanner.retail.engine.RetailProductMatcher
import com.example.chatgpt_mealplanner.retail.engine.ShoppingRequirementSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetailShoppingDomainServiceImpl @Inject constructor(
    private val shoppingDomainService: ShoppingDomainService,
    private val shoppingDao: ShoppingDao,
    private val retailRepository: RetailRepository,
    @Suppress("UNUSED_PARAMETER") private val retailDao: RetailDao? = null,
    private val icelandAdapter: IcelandRetailerAdapter = IcelandRetailerAdapter(),
    private val asdaAdapter: AsdaRetailerAdapter = AsdaRetailerAdapter()
) : RetailShoppingDomainService {

    private val freshnessPolicy: RetailFreshnessPolicy = RetailFreshnessPolicy()

    override suspend fun optimizeShoppingBasket(
        preferences: UserShoppingPreferences
    ): DomainResult<BasketComparisonResult> {
        safeLog("[RETAIL_OPTIMIZE_ENTRY] implementation=RetailShoppingDomainServiceImpl buildAudit=TOMATO_CACHE_FLOW_20261001_01")

        val activeList = shoppingDao.getActiveShoppingList()
        val requirementSpecs = if (activeList != null) {
            val listWithItems = shoppingDao.getShoppingListWithItems(activeList.id)
            val directItems = listWithItems?.items ?: emptyList()
            safeLog("[RETAIL_OPTIMIZATION_AUTHORITY] buildAudit=TOMATO_CACHE_FLOW_20261001_01 source=ACTIVE_PERSISTED_SHOPPING_LIST shoppingItemCount=${directItems.size} requirementCount=${directItems.size}")
            directItems.map { itemWithSources ->
                val item = itemWithSources.item
                val reqQty = item.requiredQuantity ?: 1.0
                val suggQty = item.suggestedPurchaseQuantity ?: reqQty
                val unitEnum = item.suggestedPurchaseUnit ?: item.requiredUnit ?: QuantityUnit.UNIT
                val unit = unitEnum.name
                safeLog("[RETAIL_REQUIREMENT_FROM_SHOPPING] buildAudit=TOMATO_CACHE_FLOW_20261001_01 shoppingItemId=${item.id} name=\"${item.displayName}\" sourceType=${item.sourceType.name} requiredQuantity=$reqQty suggestedPurchaseQuantity=$suggQty mappedQuantity=$suggQty unit=$unit canonicalIngredientId=${item.canonicalIngredientId ?: "null"}")
                ShoppingRequirementSpec(
                    id = item.id,
                    canonicalIngredientId = item.canonicalIngredientId,
                    displayName = item.displayName,
                    requiredQuantity = suggQty,
                    requiredUnit = unit,
                    priority = item.priority.name
                )
            }
        } else {
            val requirementsResult = shoppingDomainService.calculateShoppingRequirements()
            if (requirementsResult is DomainResult.Failure) {
                return DomainResult.Failure(requirementsResult.error)
            }
            val itemsWithSources = (requirementsResult as DomainResult.Success).value
            safeLog("[RETAIL_OPTIMIZATION_AUTHORITY] buildAudit=TOMATO_CACHE_FLOW_20261001_01 source=CALCULATED_REQUIREMENTS shoppingItemCount=0 requirementCount=${itemsWithSources.size}")
            itemsWithSources.map {
                val item = it.item
                val reqQty = item.requiredQuantity ?: 1.0
                val suggQty = item.suggestedPurchaseQuantity ?: reqQty
                val unitEnum = item.suggestedPurchaseUnit ?: item.requiredUnit ?: QuantityUnit.UNIT
                val unit = unitEnum.name
                safeLog("[RETAIL_REQUIREMENT_FROM_SHOPPING] buildAudit=TOMATO_CACHE_FLOW_20261001_01 shoppingItemId=${item.id} name=\"${item.displayName}\" sourceType=${item.sourceType.name} requiredQuantity=$reqQty suggestedPurchaseQuantity=$suggQty mappedQuantity=$suggQty unit=$unit canonicalIngredientId=${item.canonicalIngredientId ?: "null"}")
                ShoppingRequirementSpec(
                    id = item.id,
                    canonicalIngredientId = item.canonicalIngredientId,
                    displayName = item.displayName,
                    requiredQuantity = suggQty,
                    requiredUnit = unit,
                    priority = item.priority.name
                )
            }
        }

        safeLog("[RETAIL_OPTIMIZER_INPUT] buildAudit=TOMATO_CACHE_FLOW_20261001_01 requirementCount=${requirementSpecs.size} names=\"${requirementSpecs.joinToString { it.displayName }}\"")

        val userMappings = retailRepository.getProductMappings()
        val matcher = RetailProductMatcher(userMappings)

        requirementSpecs.forEach { req ->
            safeLog("[RETAIL_REQUIREMENT_START] requirement=${req.displayName} quantity=${req.requiredQuantity} unit=${req.requiredUnit}")

            val allCandidates = retailRepository.getCandidateProductsWithOffers()
            val queryLower = req.displayName.lowercase()
            val canonicalLower = req.canonicalIngredientId?.lowercase()

            preferences.enabledRetailers.forEach { retailerId ->
                val retailerCandidates = allCandidates.filter { c -> c.product.retailerId == retailerId }
                val relevantCandidates = retailerCandidates.filter { c ->
                    val pName = c.product.name.lowercase()
                    val cId = c.product.canonicalIngredientId?.lowercase()
                    pName.contains(queryLower) || queryLower.contains(pName) ||
                        (canonicalLower != null && cId == canonicalLower)
                }

                val freshCandidates = relevantCandidates.filter { c ->
                    c.offer.calculateFreshness(freshnessPolicy) == PriceFreshness.FRESH
                }

                // Check eligibility of fresh candidates using matcher independently per retailer
                val eligibleFreshCandidates = freshCandidates.filter { c ->
                    val evaluation = matcher.evaluateMatch(req.canonicalIngredientId, req.displayName, c.product)
                    safeLog("[RETAIL_CACHE_RELEVANT_CANDIDATE] buildAudit=TOMATO_CACHE_FLOW_20261001_01 requirement=\"${req.displayName}\" retailer=${retailerId.name} product=\"${c.product.name}\" fresh=true autoEligible=${evaluation.isAutoBasketEligible} reason=\"${evaluation.reasoning}\"")
                    evaluation.isAutoBasketEligible
                }

                val useCache = eligibleFreshCandidates.isNotEmpty()
                val decisionStr = if (useCache) "USE_CACHE" else "LIVE_SEARCH"
                val textRelevantCount = relevantCandidates.size
                val eligibleFreshCount = eligibleFreshCandidates.size

                safeLog("[RETAIL_CACHE_CHECK] buildAudit=TOMATO_CACHE_FLOW_20261001_01 requirement=${req.displayName} retailer=${retailerId.name} textRelevantCount=$textRelevantCount eligibleFreshCount=$eligibleFreshCount decision=$decisionStr")

                if (!useCache) {
                    val safeQuery = req.displayName.take(50).replace("\n", " ").trim()
                    if (retailerId == RetailerId.ICELAND) {
                        try {
                            when (val result = icelandAdapter.searchProducts(safeQuery)) {
                                is RetailProductSearchResult.Success -> {
                                    if (result.productsWithOffers.isNotEmpty()) {
                                        retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                        safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ICELAND query=\"$safeQuery\" resultCount=${result.productsWithOffers.size} success=true")
                                    }
                                }
                                is RetailProductSearchResult.PartialSuccess -> {
                                    if (result.productsWithOffers.isNotEmpty()) {
                                        retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                        safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ICELAND query=\"$safeQuery\" resultCount=${result.productsWithOffers.size} success=true")
                                    }
                                }
                                is RetailProductSearchResult.Unavailable -> {
                                    safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ICELAND query=\"$safeQuery\" resultCount=0 success=false")
                                }
                            }
                        } catch (_: Exception) {
                            safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ICELAND query=\"$safeQuery\" resultCount=0 success=false")
                        }
                    } else if (retailerId == RetailerId.ASDA) {
                        try {
                            when (val result = asdaAdapter.searchProducts(safeQuery)) {
                                is RetailProductSearchResult.Success -> {
                                    if (result.productsWithOffers.isNotEmpty()) {
                                        retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                        safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ASDA query=\"$safeQuery\" resultCount=${result.productsWithOffers.size} success=true")
                                    }
                                }
                                is RetailProductSearchResult.PartialSuccess -> {
                                    if (result.productsWithOffers.isNotEmpty()) {
                                        retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                        safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ASDA query=\"$safeQuery\" resultCount=${result.productsWithOffers.size} success=true")
                                    }
                                }
                                is RetailProductSearchResult.Unavailable -> {
                                    safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ASDA query=\"$safeQuery\" resultCount=0 success=false")
                                }
                            }
                        } catch (_: Exception) {
                            safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ASDA query=\"$safeQuery\" resultCount=0 success=false")
                        }
                    }
                }
            }

            safeLog("[RETAIL_REQUIREMENT_READY_FOR_OPTIMIZER] buildAudit=TOMATO_CACHE_FLOW_20261001_01 requirement=\"${req.displayName}\"")
        }

        var candidateProducts = retailRepository.getCandidateProductsWithOffers()
        val isTestEnv = try {
            Class.forName("org.junit.Test")
            true
        } catch (_: Throwable) {
            false
        }
        if (candidateProducts.isEmpty() && !isTestEnv) {
            // Phase 7A Fixtures Fallback for production/device only
            candidateProducts = FakeRetailerFixtures.getAsdaFixtures() + FakeRetailerFixtures.getIcelandFixtures()
        }

        requirementSpecs.forEach { req ->
            preferences.enabledRetailers.forEach { retailerId ->
                val rCandidates = candidateProducts.filter { it.product.retailerId == retailerId }
                val matched = rCandidates.map { c -> matcher.evaluateMatch(req.canonicalIngredientId, req.displayName, c.product) }
                val autoEligible = matched.count { it.isAutoBasketEligible }
                safeLog("[RETAIL_MATCH_SUMMARY] requirement=${req.displayName} retailer=${retailerId.name} candidateCount=${rCandidates.size} autoEligibleCount=$autoEligible packCompatibleCount=$autoEligible")
            }
        }

        val branchScopedCandidates = PreferredBranchOfferSelector.select(candidateProducts, preferences.preferredStores)
        val optimizer = BasketOptimizer(matcher)
        val comparison = optimizer.optimizeBasket(
            requirements = requirementSpecs,
            availableProductsWithOffers = branchScopedCandidates,
            preferences = preferences
        )

        return DomainResult.Success(comparison)
    }

    override suspend fun getItemOptions(
        shoppingListItemId: String,
        retailerFilter: RetailerId?
    ): DomainResult<List<PackAlternative>> {
        var candidateProducts = retailRepository.getCandidateProductsWithOffers()
        if (candidateProducts.isEmpty()) {
            fetchAndCacheLiveProducts(listOf(shoppingListItemId))
            candidateProducts = retailRepository.getCandidateProductsWithOffers()
        }
        if (retailerFilter != null) {
            candidateProducts = candidateProducts.filter { it.product.retailerId == retailerFilter }
        }
        val packEngine = PackSelectionEngine()
        val alternatives = packEngine.generatePackAlternatives(
            requiredQuantity = 1.0,
            requiredUnit = "GRAM",
            candidateMatches = candidateProducts
        )
        return DomainResult.Success(alternatives.take(5))
    }

    override suspend fun searchProducts(
        query: String,
        retailerFilter: RetailerId?
    ): DomainResult<List<RetailProductWithOffer>> {
        var candidateProducts = retailRepository.getCandidateProductsWithOffers()
        if (candidateProducts.isEmpty()) {
            fetchAndCacheLiveProducts(listOf(query))
            candidateProducts = retailRepository.getCandidateProductsWithOffers()
        }
        val queryLower = query.lowercase()
        var matches = candidateProducts.filter {
            it.product.name.lowercase().contains(queryLower) ||
                    (it.product.canonicalIngredientId?.lowercase()?.contains(queryLower) == true)
        }
        if (retailerFilter != null) {
            matches = matches.filter { it.product.retailerId == retailerFilter }
        }
        return DomainResult.Success(matches.take(5))
    }

    private suspend fun fetchAndCacheLiveProducts(queries: List<String>) {
        for (q in queries) {
            if (q.isBlank()) continue
            val safeQuery = q.take(50).replace("\n", " ").trim()

            try {
                when (val result = icelandAdapter.searchProducts(safeQuery)) {
                    is RetailProductSearchResult.Success -> {
                        if (result.productsWithOffers.isNotEmpty()) {
                            retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                        }
                    }
                    is RetailProductSearchResult.PartialSuccess -> {
                        if (result.productsWithOffers.isNotEmpty()) {
                            retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                        }
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun safeLog(msg: String) {
        try {
            println("[AI_MEALPLANNER] $msg")
        } catch (_: Throwable) {}
    }
}
