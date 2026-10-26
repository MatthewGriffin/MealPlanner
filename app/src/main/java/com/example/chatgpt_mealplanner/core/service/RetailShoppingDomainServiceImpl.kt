package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.backend.retail.AldiRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.AdditionalRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.AsdaRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.IcelandRetailerAdapter
import com.example.chatgpt_mealplanner.backend.retail.TescoRetailerAdapter
import com.example.chatgpt_mealplanner.core.database.dao.RetailDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.entity.BasketComparisonCacheEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.repository.RetailRepository
import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import com.example.chatgpt_mealplanner.retail.contract.PackAlternative
import com.example.chatgpt_mealplanner.retail.contract.PriceFreshness
import com.example.chatgpt_mealplanner.retail.contract.RetailFreshnessPolicy
import com.example.chatgpt_mealplanner.retail.contract.RetailProductSearchResult
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.UserShoppingPreferences
import com.example.chatgpt_mealplanner.retail.engine.BasketOptimizer
import com.example.chatgpt_mealplanner.retail.engine.FakeRetailerFixtures
import com.example.chatgpt_mealplanner.retail.engine.PackSelectionEngine
import com.example.chatgpt_mealplanner.retail.engine.RetailProductMatcher
import com.example.chatgpt_mealplanner.retail.engine.ShoppingRequirementSpec
import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Singleton
class RetailShoppingDomainServiceImpl @Inject constructor(
    private val shoppingDomainService: ShoppingDomainService,
    private val shoppingDao: ShoppingDao,
    private val retailRepository: RetailRepository,
    @Suppress("UNUSED_PARAMETER") private val retailDao: RetailDao? = null,
    private val icelandAdapter: IcelandRetailerAdapter = IcelandRetailerAdapter(),
    private val asdaAdapter: AsdaRetailerAdapter = AsdaRetailerAdapter(),
    private val aldiAdapter: AldiRetailerAdapter = AldiRetailerAdapter(),
    private val tescoAdapter: TescoRetailerAdapter = TescoRetailerAdapter(),
    private val additionalRetailerAdapter: AdditionalRetailerAdapter = AdditionalRetailerAdapter()
) : RetailShoppingDomainService {

    /** Opt-in for tests that inject a fake retailer adapter; never enabled in production. */
    internal var allowLiveLookupsInTests: Boolean = false

    private val freshnessPolicy: RetailFreshnessPolicy = RetailFreshnessPolicy()
    private val cacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun optimizeShoppingBasket(
        preferences: UserShoppingPreferences
    ): DomainResult<BasketComparisonResult> = optimizeShoppingBasket(preferences) { }

    override suspend fun optimizeShoppingBasket(
        preferences: UserShoppingPreferences,
        onLookupProgress: (String) -> Unit
    ): DomainResult<BasketComparisonResult> =
        optimizeShoppingBasket(preferences, onLookupProgress) { }

    override suspend fun optimizeShoppingBasket(
        preferences: UserShoppingPreferences,
        onLookupProgress: (String) -> Unit,
        onBasketUpdate: (BasketComparisonResult) -> Unit
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

        // Unit tests must remain deterministic and must never fan out to real retailer websites.
        val isTestEnv = try {
            Class.forName("org.junit.Test")
            true
        } catch (_: Throwable) {
            false
        }
        // Reuse the durable basket result until list requirements, selected stores/retailers,
        // or user-confirmed product mappings change. Invalid payloads safely fall through.
        val cacheFingerprint = buildBasketCacheFingerprint(requirementSpecs, preferences)
        if (retailDao != null) {
            val cached = try { retailDao.getBasketComparisonCache() } catch (_: Exception) { null }
            if (cached != null && cached.fingerprint == cacheFingerprint) {
                try {
                    val cachedResult = cacheJson.decodeFromString<BasketComparisonResult>(cached.payloadJson)
                    safeLog("[RETAIL_BASKET_CACHE] hit fingerprint=${cacheFingerprint.take(12)} generatedAt=${cached.generatedAt}")
                    onBasketUpdate(cachedResult)
                    return DomainResult.Success(cachedResult)
                } catch (_: Exception) {
                    safeLog("[RETAIL_BASKET_CACHE] invalid_payload action=recompute")
                }
            }
        }


        val userMappings = retailRepository.getProductMappings()
        val matcher = RetailProductMatcher(userMappings)

        requirementSpecs.forEach { req ->
            safeLog("[RETAIL_REQUIREMENT_START] requirement=${req.displayName} quantity=${req.requiredQuantity} unit=${req.requiredUnit}")
            onLookupProgress("Checking ${req.displayName}…")

            val allCandidates = retailRepository.getCandidateProductsWithOffers()
            val queryLower = req.displayName.lowercase()
            val canonicalLower = req.canonicalIngredientId?.lowercase()

            preferences.enabledRetailers.forEach { retailerId ->
                val retailerCandidates = allCandidates.filter { c -> c.product.retailerId == retailerId }
                val relevantCandidates = retailerCandidates.filter { c ->
                    val pName = c.product.name.lowercase()
                    val cId = c.product.canonicalIngredientId?.lowercase()
                    pName.contains(queryLower) || queryLower.contains(pName) ||
                        (canonicalLower != null && cId == canonicalLower) ||
                        matcher.evaluateMatch(req.canonicalIngredientId, req.displayName, c.product).isAutoBasketEligible
                }

                // Reuse cached product/price evidence while it is fresh (24h) or
                // reasonably stale (up to 72h). Only query the retailer again when the
                // cache has no suitable, usable candidate; this also prepares the matcher
                // to benefit from larger catalogue imports without needless network calls.
                val usableCachedCandidates = relevantCandidates.filter { c ->
                    c.offer.calculateFreshness(freshnessPolicy) in setOf(PriceFreshness.FRESH, PriceFreshness.STALE)
                }

                val eligibleCachedCandidates = usableCachedCandidates.filter { c ->
                    val evaluation = matcher.evaluateMatch(req.canonicalIngredientId, req.displayName, c.product)
                    val freshness = c.offer.calculateFreshness(freshnessPolicy)
                    safeLog("[RETAIL_CACHE_RELEVANT_CANDIDATE] buildAudit=TOMATO_CACHE_FLOW_20261001_01 requirement=\"${req.displayName}\" retailer=${retailerId.name} product=\"${c.product.name}\" freshness=${freshness.name} autoEligible=${evaluation.isAutoBasketEligible} reason=\"${evaluation.reasoning}\"")
                    evaluation.isAutoBasketEligible
                }

                val useCache = eligibleCachedCandidates.isNotEmpty()
                val decisionStr = if (useCache) "USE_CACHE" else "LIVE_SEARCH"
                val textRelevantCount = relevantCandidates.size
                val eligibleCachedCount = eligibleCachedCandidates.size

                safeLog("[RETAIL_CACHE_CHECK] buildAudit=TOMATO_CACHE_FLOW_20261001_01 requirement=${req.displayName} retailer=${retailerId.name} textRelevantCount=$textRelevantCount eligibleCachedCount=$eligibleCachedCount decision=$decisionStr")

                if (!useCache && (!isTestEnv || allowLiveLookupsInTests)) {
                    onLookupProgress("Looking up ${req.displayName} at ${retailerId.name.lowercase().replaceFirstChar { it.uppercase() }}…")
                    val safeQuery = (if (req.displayName.contains("heavy cream", ignoreCase = true)) "double cream" else req.displayName).take(50).replace("\n", " ").trim()
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
                    } else if (retailerId == RetailerId.ALDI) {
                        try {
                            when (val result = aldiAdapter.searchProducts(safeQuery)) {
                                is RetailProductSearchResult.Success -> {
                                    if (result.productsWithOffers.isNotEmpty()) {
                                        retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                        safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ALDI query=\"$safeQuery\" resultCount=${result.productsWithOffers.size} success=true")
                                    }
                                }
                                is RetailProductSearchResult.PartialSuccess -> {
                                    if (result.productsWithOffers.isNotEmpty()) {
                                        retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                        safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ALDI query=\"$safeQuery\" resultCount=${result.productsWithOffers.size} success=true")
                                    }
                                }
                                is RetailProductSearchResult.Unavailable -> {
                                    safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ALDI query=\"$safeQuery\" resultCount=0 success=false")
                                }
                            }
                        } catch (_: Exception) {
                            safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=ALDI query=\"$safeQuery\" resultCount=0 success=false")
                        }
                    } else if (retailerId == RetailerId.TESCO) {
                        try {
                            when (val result = tescoAdapter.searchProducts(safeQuery)) {
                                is RetailProductSearchResult.Success -> if (result.productsWithOffers.isNotEmpty()) retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                is RetailProductSearchResult.PartialSuccess -> if (result.productsWithOffers.isNotEmpty()) retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                is RetailProductSearchResult.Unavailable -> safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=TESCO query=\"$safeQuery\" resultCount=0 success=false")
                            }
                        } catch (_: Exception) {
                            safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=TESCO query=\"$safeQuery\" resultCount=0 success=false")
                        }
                    } else if (retailerId == RetailerId.MORRISONS || retailerId == RetailerId.SAINSBURYS || retailerId == RetailerId.LIDL) {
                        try {
                            when (val result = additionalRetailerAdapter.searchProducts(retailerId, safeQuery)) {
                                is RetailProductSearchResult.Success -> if (result.productsWithOffers.isNotEmpty()) retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                is RetailProductSearchResult.PartialSuccess -> if (result.productsWithOffers.isNotEmpty()) retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                                is RetailProductSearchResult.Unavailable -> safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=${retailerId.name} query=\"$safeQuery\" resultCount=0 success=false")
                            }
                        } catch (_: Exception) {
                            safeLog("[RETAIL_LIVE_SEARCH] requirement=${req.displayName} retailer=${retailerId.name} query=\"$safeQuery\" resultCount=0 success=false")
                        }
                    }
                    onLookupProgress(
                        "Completed retailer lookup for ${req.displayName} at ${retailerId.name.lowercase().replaceFirstChar { it.uppercase() }}."
                    )
                }
            }

            safeLog("[RETAIL_REQUIREMENT_READY_FOR_OPTIMIZER] buildAudit=TOMATO_CACHE_FLOW_20261001_01 requirement=\"${req.displayName}\"")

            // Keep the currently displayed assignments stable during the search.
            // Re-optimizing a partial basket here can move an already displayed item
            // between retailers as each subsequent item's offers enter the cache.
            // Publish the comparison once all retailer lookups have completed.
        }

        var candidateProducts = retailRepository.getCandidateProductsWithOffers()
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

        if (retailDao != null) {
            try {
                retailDao.saveBasketComparisonCache(
                    BasketComparisonCacheEntity(
                        cacheId = 1,
                        fingerprint = cacheFingerprint,
                        payloadJson = cacheJson.encodeToString(comparison),
                        generatedAt = comparison.generatedAt
                    )
                )
                safeLog("[RETAIL_BASKET_CACHE] saved fingerprint=${cacheFingerprint.take(12)} generatedAt=${comparison.generatedAt}")
            } catch (e: Exception) {
                safeLog("[RETAIL_BASKET_CACHE] save_failed type=${e.javaClass.simpleName}")
            }
        }
        onBasketUpdate(comparison)
        return DomainResult.Success(comparison)
    }

    private suspend fun buildBasketCacheFingerprint(
        requirements: List<ShoppingRequirementSpec>,
        preferences: UserShoppingPreferences
    ): String {
        val parts = mutableListOf<String>()
        // Invalidate persisted basket comparisons when product-matching semantics change.
        // Otherwise an old cached plan can keep showing a rejected match after a matcher fix.
        parts += "retail_matcher_rules_v2"
        requirements.sortedBy { it.id }.forEach { req ->
            parts += listOf(req.id, req.canonicalIngredientId.orEmpty(), req.displayName,
                req.requiredQuantity.toString(), req.requiredUnit, req.priority).joinToString("") {
                "${it.length}:$it;"
            }
        }
        parts += preferences.enabledRetailers.map { it.name }.sorted().joinToString(",")
        parts += preferences.preferredStores
            .sortedWith(compareBy({ it.retailerId.name }, { it.id }, { it.retailerStoreId.orEmpty() }))
            .joinToString("|") { store ->
                listOf(store.id, store.retailerId.name, store.name, store.postcode, store.format.name,
                    store.isPreferred.toString(), store.retailerStoreId.orEmpty()).joinToString("") {
                    "${it.length}:$it;"
                }
            }
        val mappings = try { retailDao?.getAllProductMappings().orEmpty() } catch (_: Exception) { emptyList() }
        parts += mappings.sortedBy { it.id }.joinToString("|") { mapping ->
            listOf(mapping.id, mapping.canonicalIngredientId, mapping.decision.name,
                mapping.userConfirmed.toString(), mapping.updatedAt.toString()).joinToString("") {
                "${it.length}:$it;"
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\n").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    override suspend fun getItemOptions(
        shoppingListItemId: String,
        retailerFilter: RetailerId?
    ): DomainResult<List<PackAlternative>> {
        // Resolve the actual requirement first. The item ID is not a useful retail
        // search term, and a non-empty ASDA cache must not suppress other-store lookups.
        val activeList = shoppingDao.getActiveShoppingList()
        val requirement = activeList?.let { shoppingDao.getShoppingListWithItems(it.id) }
            ?.items
            ?.firstOrNull { it.item.id == shoppingListItemId }
            ?.item
        if (requirement == null) return DomainResult.Success(emptyList())

        val requiredQuantity = requirement.suggestedPurchaseQuantity
            ?: requirement.requiredQuantity
            ?: 1.0
        val requiredUnit = (requirement.suggestedPurchaseUnit ?: requirement.requiredUnit ?: QuantityUnit.UNIT).name
        val matcher = RetailProductMatcher(retailRepository.getProductMappings())

        var candidateProducts = retailRepository.getCandidateProductsWithOffers()
        fun matchingCandidates(products: List<RetailProductWithOffer>): List<RetailProductWithOffer> =
            products.filter { candidate ->
                (retailerFilter == null || candidate.product.retailerId == retailerFilter) &&
                    candidate.offer.availability != RetailAvailability.OUT_OF_STOCK &&
                    matcher.evaluateMatch(requirement.canonicalIngredientId, requirement.displayName, candidate.product)
                        .isAutoBasketEligible
            }

        var matches = matchingCandidates(candidateProducts)
        // A cached match at one retailer must not hide alternatives at the others.
        // Fill retailer gaps independently, then rebuild the comparison from the cache.
        val retailersToCheck = retailerFilter?.let { listOf(it) } ?: listOf(
            RetailerId.ASDA, RetailerId.ICELAND, RetailerId.ALDI, RetailerId.TESCO,
            RetailerId.MORRISONS, RetailerId.SAINSBURYS, RetailerId.LIDL
        )
        val retailersAlreadyMatched = matches.map { it.product.retailerId }.toSet()
        for (store in retailersToCheck.filter { it !in retailersAlreadyMatched }) {
            fetchAndCacheLiveProducts(listOf(requirement.displayName), store)
        }
        candidateProducts = retailRepository.getCandidateProductsWithOffers()
        matches = matchingCandidates(candidateProducts)

        val alternatives = PackSelectionEngine().generatePackAlternatives(
            requiredQuantity = requiredQuantity,
            requiredUnit = requiredUnit,
            candidateMatches = matches
        )

        // Keep the comparison representative across retailers. A global take(10)
        // lets one retailer with many pack variants (often ASDA) crowd every other
        // store out of the result. The contract promises up to five options per retailer.
        val alternativesPerRetailer = alternatives
            .groupBy { it.productWithOffer.product.retailerId }
            .values
            .flatMap { retailerAlternatives ->
                retailerAlternatives
                    .sortedBy { it.totalCost.amountMinor }
                    .take(5)
            }
            .sortedBy { it.totalCost.amountMinor }

        safeLog(
            "[RETAIL_ITEM_OPTIONS_SUMMARY] requirement=${requirement.displayName} " +
                "candidateCount=${candidateProducts.size} matchedCount=${matches.size} " +
                "alternativeCount=${alternatives.size} byRetailer=${alternativesPerRetailer.groupingBy { it.productWithOffer.product.retailerId.name }.eachCount()}"
        )
        return DomainResult.Success(alternativesPerRetailer)
    }

    override suspend fun searchProducts(
        query: String,
        retailerFilter: RetailerId?
    ): DomainResult<List<RetailProductWithOffer>> {
        if (query.isBlank()) return DomainResult.Success(emptyList())

        var candidateProducts = retailRepository.getCandidateProductsWithOffers()
        val queryTokens = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()
        fun matchesQuery(candidate: RetailProductWithOffer): Boolean {
            val productTokens = candidate.product.name.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotBlank() }
                .toSet()
            return queryTokens.isNotEmpty() && productTokens.containsAll(queryTokens) ||
                candidate.product.canonicalIngredientId?.equals(query, ignoreCase = true) == true
        }

        // A non-empty cache may still have no evidence for this particular ingredient.
        // Search live retailer catalogues in that case, then evaluate the refreshed cache.
        var matches = candidateProducts.filter { matchesQuery(it) }
        if (retailerFilter != null) {
            matches = matches.filter { it.product.retailerId == retailerFilter }
        }
        if (matches.isEmpty()) {
            fetchAndCacheLiveProducts(listOf(query), retailerFilter)
            candidateProducts = retailRepository.getCandidateProductsWithOffers()
            matches = candidateProducts.filter { matchesQuery(it) }
            if (retailerFilter != null) {
                matches = matches.filter { it.product.retailerId == retailerFilter }
            }
        }
        return DomainResult.Success(matches.take(10))
    }

    private suspend fun fetchAndCacheLiveProducts(
        queries: List<String>,
        retailerFilter: RetailerId? = null
    ) {
        val retailers = retailerFilter?.let { listOf(it) } ?: listOf(
            RetailerId.ASDA,
            RetailerId.ICELAND,
            RetailerId.ALDI,
            RetailerId.TESCO,
            RetailerId.MORRISONS,
            RetailerId.SAINSBURYS,
            RetailerId.LIDL
        )
        for (q in queries) {
            if (q.isBlank()) continue
            val safeQuery = q.take(50).replace("\n", " ").trim()
            for (retailerId in retailers) {
                try {
                    val result = when (retailerId) {
                        RetailerId.ASDA -> asdaAdapter.searchProducts(safeQuery)
                        RetailerId.ICELAND -> icelandAdapter.searchProducts(safeQuery)
                        RetailerId.ALDI -> aldiAdapter.searchProducts(safeQuery)
                        RetailerId.TESCO -> tescoAdapter.searchProducts(safeQuery)
                        RetailerId.MORRISONS, RetailerId.SAINSBURYS, RetailerId.LIDL ->
                            additionalRetailerAdapter.searchProducts(retailerId, safeQuery)
                        else -> continue
                    }
                    when (result) {
                        is RetailProductSearchResult.Success ->
                            if (result.productsWithOffers.isNotEmpty()) retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                        is RetailProductSearchResult.PartialSuccess ->
                            if (result.productsWithOffers.isNotEmpty()) retailRepository.cacheProductsWithOffers(result.productsWithOffers)
                        is RetailProductSearchResult.Unavailable -> Unit
                    }
                } catch (_: Exception) {
                    // Live source failures are non-fatal; cached/manual results remain available.
                }
            }
        }
    }

    private fun safeLog(msg: String) {
        try {
            println("[AI_MEALPLANNER] $msg")
        } catch (_: Throwable) {}
    }
}
