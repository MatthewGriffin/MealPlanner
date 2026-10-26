package com.example.chatgpt_mealplanner.retail.engine

import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import com.example.chatgpt_mealplanner.retail.contract.BasketFeasibilityTier
import com.example.chatgpt_mealplanner.retail.contract.BasketLine
import com.example.chatgpt_mealplanner.retail.contract.BasketPlan
import com.example.chatgpt_mealplanner.retail.contract.MatchConfidence
import com.example.chatgpt_mealplanner.retail.contract.Money
import com.example.chatgpt_mealplanner.retail.contract.PriceFreshness
import com.example.chatgpt_mealplanner.retail.contract.RetailAvailability
import com.example.chatgpt_mealplanner.retail.contract.RetailFreshnessPolicy
import com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.UserShoppingPreferences
import java.util.UUID

data class ShoppingRequirementSpec(
    val id: String,
    val canonicalIngredientId: String?,
    val displayName: String,
    val requiredQuantity: Double,
    val requiredUnit: String,
    val priority: String // ESSENTIAL, STAPLE, OPTIONAL
)

class BasketOptimizer(
    private val matcher: RetailProductMatcher,
    private val packEngine: PackSelectionEngine = PackSelectionEngine(),
    private val freshnessPolicy: RetailFreshnessPolicy = RetailFreshnessPolicy()
) {

    fun optimizeBasket(
        requirements: List<ShoppingRequirementSpec>,
        availableProductsWithOffers: List<RetailProductWithOffer>,
        preferences: UserShoppingPreferences = UserShoppingPreferences()
    ): BasketComparisonResult {
        safeLog("[BASKET_OPTIMIZER_ENTRY] buildAudit=SHOPPING_AUDIT_20261001_02 requirementCount=${requirements.size} candidateCount=${availableProductsWithOffers.size}")
        val emptyPlan = BasketPlan(
            planId = UUID.randomUUID().toString(),
            stores = emptySet(),
            lines = emptyList(),
            feasibilityTier = BasketFeasibilityTier.COMPLETE,
            basketSubtotal = Money.zero(),
            estimatedTripPenalty = Money.zero(),
            estimatedWastePenaltyScore = 0.0,
            unmatchedRequirements = emptyList(),
            uncertainItems = emptyList(),
            explanationText = "No shopping requirements specified."
        )

        if (requirements.isEmpty()) {
            return BasketComparisonResult(
                selectedPlan = emptyPlan,
                minimumSavingsForExtraStore = preferences.minimumSavingsForExtraStore,
                extraStoreThresholdApplied = false,
                feasibilityTier = BasketFeasibilityTier.COMPLETE,
                unmatchedRequirements = emptyList(),
                uncertainItems = emptyList()
            )
        }

        val enabledStores = preferences.enabledRetailers
        val singleStorePlans = mutableListOf<BasketPlan>()

        // 1. Build single-store plans for each enabled retailer
        enabledStores.forEach { storeId ->
            val storeProducts = availableProductsWithOffers.filter { it.product.retailerId == storeId }
            val plan = buildPlanForStores(setOf(storeId), requirements, storeProducts, preferences)
            singleStorePlans.add(plan)
        }

        val bestSingleStorePlan = singleStorePlans.sortedWith(
            compareBy<BasketPlan> { it.feasibilityTier }
                .thenBy { it.basketSubtotal }
        ).firstOrNull()

        // 2. Build multi-store split plan
        val splitPlan = buildPlanForStores(enabledStores, requirements, availableProductsWithOffers, preferences)

        var rawSaving: Money? = null
        var thresholdApplied = false
        var selectedPlan: BasketPlan = splitPlan

        // 3. Evaluate second-store minimum savings threshold rule
        if (bestSingleStorePlan != null && bestSingleStorePlan.feasibilityTier == BasketFeasibilityTier.COMPLETE && splitPlan.stores.size > 1) {
            val calcSaving = bestSingleStorePlan.basketSubtotal - splitPlan.basketSubtotal
            rawSaving = calcSaving
            if (calcSaving < preferences.minimumSavingsForExtraStore) {
                thresholdApplied = true
                selectedPlan = bestSingleStorePlan.copy(
                    explanationText = "Single-store shop at ${bestSingleStorePlan.stores.first().name} selected. Splitting stores saves ${calcSaving.toFormattedString()}, which is below your ${preferences.minimumSavingsForExtraStore.toFormattedString()} extra-store minimum savings threshold."
                )
            }
        }

        if (!thresholdApplied) {
            val allCandidatePlans = (singleStorePlans + listOf(splitPlan)).filter { it.lines.isNotEmpty() }
            selectedPlan = allCandidatePlans.sortedWith(
                compareBy<BasketPlan> { it.feasibilityTier }
                    .thenBy { it.basketSubtotal + it.estimatedTripPenalty }
            ).firstOrNull() ?: bestSingleStorePlan ?: splitPlan
        }

        val staleCount = availableProductsWithOffers.count { it.offer.calculateFreshness(freshnessPolicy) == PriceFreshness.STALE }
        val unknownStockCount = availableProductsWithOffers.count { it.offer.availability == RetailAvailability.UNKNOWN }

        return BasketComparisonResult(
            selectedPlan = selectedPlan,
            bestSingleStorePlan = bestSingleStorePlan,
            bestMultiStorePlan = splitPlan,
            alternativePlans = singleStorePlans.filter { it.planId != selectedPlan.planId },
            rawMultiStoreSaving = rawSaving,
            minimumSavingsForExtraStore = preferences.minimumSavingsForExtraStore,
            extraStoreThresholdApplied = thresholdApplied,
            feasibilityTier = selectedPlan.feasibilityTier,
            unmatchedRequirements = selectedPlan.unmatchedRequirements,
            uncertainItems = selectedPlan.uncertainItems,
            staleOfferCount = staleCount,
            unknownAvailabilityCount = unknownStockCount
        )
    }

    private fun buildPlanForStores(
        targetStores: Set<RetailerId>,
        requirements: List<ShoppingRequirementSpec>,
        availableProducts: List<RetailProductWithOffer>,
        preferences: UserShoppingPreferences
    ): BasketPlan {
        val lines = mutableListOf<BasketLine>()
        val unmatched = mutableListOf<String>()
        val uncertain = mutableListOf<String>()
        var subtotal = Money.zero()
        var totalWasteScore = 0.0

        requirements.forEach { req ->
            safeLog("[SHOPPING_REQUIREMENT] name=${req.displayName} requiredQuantity=${req.requiredQuantity} unit=${req.requiredUnit} inventoryApplied=N/A remainingQuantity=${req.requiredQuantity}")
            safeLog("[BASKET_REQUIREMENT_INPUT] buildAudit=TOMATO_MATCH_AUDIT_20261001_01 name=\"${req.displayName}\" quantity=${req.requiredQuantity} unit=${req.requiredUnit}")

            val candidates = availableProducts.filter { it.product.retailerId in targetStores && it.offer.availability != RetailAvailability.OUT_OF_STOCK }
            val matchedCandidates = candidates.mapNotNull { candidate ->
                safeLog("[BASKET_MATCHER_CALL] buildAudit=TOMATO_MATCH_AUDIT_20261001_01 requirement=\"${req.displayName}\" candidate=\"${candidate.product.name}\"")
                val match = matcher.evaluateMatch(req.canonicalIngredientId, req.displayName, candidate.product)
                safeLog("[BASKET_MATCHER_RETURN] buildAudit=TOMATO_MATCH_AUDIT_20261001_01 requirement=\"${req.displayName}\" candidate=\"${candidate.product.name}\" matchType=${match.matchType} autoEligible=${match.isAutoBasketEligible} reason=\"${match.reasoning}\"")
                if (match.isAutoBasketEligible) Pair(candidate, match) else null
            }

            if (matchedCandidates.isEmpty()) {
                if (req.priority.equals("ESSENTIAL", ignoreCase = true)) {
                    unmatched.add(req.id)
                    safeLog("[BASKET_UNASSIGNED] requirement=${req.displayName} quantity=${req.requiredQuantity} unit=${req.requiredUnit} reason=\"Essential requirement unassigned - out of stock or no matching candidates found\"")
                }
            } else {
                // Product-form compatibility outranks price: diced chicken must beat
                // breast fillets when the requirement explicitly asks for diced chicken.
                // PackSelectionEngine sorts by price, so rank before its five-candidate cap
                // and select the final alternative by match confidence before total cost.
                val confidenceRank = mapOf(
                    MatchConfidence.EXACT to 0,
                    MatchConfidence.HIGH to 1,
                    MatchConfidence.MEDIUM to 2,
                    MatchConfidence.LOW to 3,
                    MatchConfidence.REQUIRES_CONFIRMATION to 4
                )
                val rankedMatches = matchedCandidates.sortedWith(
                    compareBy<Pair<RetailProductWithOffer, RetailMatchCandidate>> {
                        confidenceRank[it.second.confidence] ?: Int.MAX_VALUE
                    }.thenBy { it.first.offer.price.amountMinor }
                )
                val candidateProducts = rankedMatches.map { it.first }
                val alternatives = packEngine.generatePackAlternatives(
                    requiredQuantity = req.requiredQuantity,
                    requiredUnit = req.requiredUnit,
                    candidateMatches = candidateProducts,
                    enableLoyalty = preferences.enableAsdaRewards || preferences.enableIcelandBonusCard
                )

                val matchByProductId = rankedMatches.associate {
                    it.first.product.retailerProductId to it.second
                }
                val bestAlt = alternatives.minWithOrNull(
                    compareBy<com.example.chatgpt_mealplanner.retail.contract.PackAlternative> {
                        confidenceRank[matchByProductId[it.productWithOffer.product.retailerProductId]?.confidence]
                            ?: Int.MAX_VALUE
                    }.thenBy { it.totalCost.amountMinor }
                     .thenBy { it.expectedRemainder }
                )
                if (bestAlt != null) {
                    val matchMeta = matchByProductId[bestAlt.productWithOffer.product.retailerProductId]
                        ?: matcher.evaluateMatch(req.canonicalIngredientId, req.displayName, bestAlt.productWithOffer.product)

                    val line = BasketLine(
                        shoppingRequirementId = req.id,
                        requirementDisplayName = req.displayName,
                        retailerId = bestAlt.productWithOffer.product.retailerId,
                        productWithOffer = bestAlt.productWithOffer,
                        packCountToBuy = bestAlt.packCountToBuy,
                        lineTotalCost = bestAlt.totalCost,
                        requirementCovered = bestAlt.requirementCovered,
                        expectedRemainder = bestAlt.expectedRemainder,
                        matchConfidence = matchMeta.confidence
                    )
                    lines.add(line)
                    subtotal += bestAlt.totalCost
                    totalWasteScore += bestAlt.wastePenaltyScore

                    safeLog("[BASKET_ASSIGNMENT] requirement=${req.displayName} retailer=${bestAlt.productWithOffer.product.retailerId.name} product=\"${bestAlt.productWithOffer.product.name}\" retailerProductId=${bestAlt.productWithOffer.product.retailerProductId} requiredQuantity=${req.requiredQuantity} requiredUnit=${req.requiredUnit} packQuantity=${bestAlt.productWithOffer.product.netQuantity ?: 1.0} packUnit=${bestAlt.productWithOffer.product.netQuantityUnit ?: "UNIT"} packCount=${bestAlt.packCountToBuy} packsSelected=${bestAlt.packCountToBuy} totalPurchasedQuantity=${bestAlt.totalQuantityPurchased} pricePerPackPence=${bestAlt.productWithOffer.offer.price.amountMinor} totalPricePence=${bestAlt.totalCost.amountMinor} matchType=${matchMeta.confidence.name} sourceType=${bestAlt.productWithOffer.offer.sourceType.name} priceScope=${bestAlt.productWithOffer.offer.priceScope.name} reason=\"Matched and optimized\"")

                    if (matchMeta.confidence == MatchConfidence.REQUIRES_CONFIRMATION || bestAlt.productWithOffer.offer.availability == RetailAvailability.UNKNOWN) {
                        uncertain.add(req.id)
                    }
                } else if (req.priority.equals("ESSENTIAL", ignoreCase = true)) {
                    unmatched.add(req.id)
                    safeLog("[BASKET_UNASSIGNED] requirement=${req.displayName} quantity=${req.requiredQuantity} unit=${req.requiredUnit} reason=\"Essential requirement unassigned - no pack alternatives found\"")
                }
            }
        }

        val actualStoresUsed = lines.map { it.retailerId }.toSet()
        val extraStoresCount = maxOf(0, actualStoresUsed.size - 1)
        val tripPenalty = preferences.additionalStorePenalty * extraStoresCount

        val tier = when {
            unmatched.isNotEmpty() -> BasketFeasibilityTier.PARTIAL
            uncertain.isNotEmpty() -> BasketFeasibilityTier.COMPLETE_WITH_UNCERTAINTY
            else -> BasketFeasibilityTier.COMPLETE
        }

        val explanation = when {
            actualStoresUsed.isEmpty() -> "No items matched for store selection."
            actualStoresUsed.size == 1 -> "All matched requirements fulfilled at ${actualStoresUsed.first().name}."
            else -> "Split basket across ${actualStoresUsed.joinToString { it.name }}."
        }

        return BasketPlan(
            planId = UUID.randomUUID().toString(),
            stores = actualStoresUsed,
            lines = lines,
            feasibilityTier = tier,
            basketSubtotal = subtotal,
            estimatedTripPenalty = tripPenalty,
            estimatedWastePenaltyScore = totalWasteScore,
            unmatchedRequirements = unmatched,
            uncertainItems = uncertain,
            explanationText = explanation
        )
    }

    private fun safeLog(msg: String) {
        try {
            println("[AI_MEALPLANNER] $msg")
        } catch (_: Throwable) {}
    }
}
