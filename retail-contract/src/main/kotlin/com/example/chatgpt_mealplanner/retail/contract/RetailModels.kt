package com.example.chatgpt_mealplanner.retail.contract

import kotlinx.serialization.Serializable

@Serializable
sealed interface RetailPromotion {
    @Serializable
    data class SalePrice(
        val promotionalPrice: Money,
        val scope: RetailPromotionScope = RetailPromotionScope.SAME_PRODUCT
    ) : RetailPromotion

    @Serializable
    data class Multibuy(
        val requiredQuantity: Int,
        val totalPrice: Money,
        val scope: RetailPromotionScope = RetailPromotionScope.SAME_PRODUCT
    ) : RetailPromotion

    @Serializable
    data class LoyaltyPrice(
        val promotionalPrice: Money,
        val loyaltyProgram: String,
        val scope: RetailPromotionScope = RetailPromotionScope.SAME_PRODUCT
    ) : RetailPromotion

    @Serializable
    data class LoyaltyMultibuy(
        val requiredQuantity: Int,
        val totalPrice: Money,
        val loyaltyProgram: String,
        val scope: RetailPromotionScope = RetailPromotionScope.SAME_PRODUCT
    ) : RetailPromotion
}

@Serializable
data class RetailProduct(
    val retailerId: RetailerId,
    val retailerProductId: String,
    val name: String,
    val brand: String? = null,
    val canonicalIngredientId: String? = null,
    val barcode: String? = null,
    val category: String? = null,
    val imageUrl: String? = null,
    val packageType: String? = null,
    val netQuantity: Double? = null,
    val netQuantityUnit: String? = null,
    val packCount: Int? = null, // Unknown defaults to null, NEVER forced to 1
    val packSize: Double? = null,
    val packSizeUnit: String? = null,
    val storageClass: RetailStorageClass = RetailStorageClass.UNKNOWN
)

@Serializable
data class RetailOffer(
    val retailerId: RetailerId,
    val retailerProductId: String,
    val price: Money,
    val promotion: RetailPromotion? = null,
    val availability: RetailAvailability = RetailAvailability.UNKNOWN,
    val priceScope: RetailPriceScope = RetailPriceScope.ONLINE,
    val sourceType: RetailOfferSourceType = RetailOfferSourceType.LIVE,
    val observedAt: Long = System.currentTimeMillis(),
    /** Stable ID of the physical branch when the source truly reports branch-specific data. */
    val storeLocationId: String? = null
) {
    fun calculateFreshness(policy: RetailFreshnessPolicy): PriceFreshness {
        val ageMs = System.currentTimeMillis() - observedAt
        return when {
            ageMs < policy.freshMaxAgeMs -> PriceFreshness.FRESH
            ageMs < policy.staleMaxAgeMs -> PriceFreshness.STALE
            else -> PriceFreshness.UNKNOWN
        }
    }

    fun getEffectivePrice(purchasedPacks: Int, enableLoyalty: Boolean): Money {
        val prom = promotion ?: return price * purchasedPacks

        return when (prom) {
            is RetailPromotion.SalePrice -> prom.promotionalPrice * purchasedPacks
            is RetailPromotion.Multibuy -> {
                if (prom.scope == RetailPromotionScope.SAME_PRODUCT && purchasedPacks >= prom.requiredQuantity) {
                    val multiCount = purchasedPacks / prom.requiredQuantity
                    val remainder = purchasedPacks % prom.requiredQuantity
                    (prom.totalPrice * multiCount) + (price * remainder)
                } else price * purchasedPacks
            }
            is RetailPromotion.LoyaltyPrice -> {
                if (enableLoyalty) prom.promotionalPrice * purchasedPacks else price * purchasedPacks
            }
            is RetailPromotion.LoyaltyMultibuy -> {
                if (enableLoyalty && prom.scope == RetailPromotionScope.SAME_PRODUCT && purchasedPacks >= prom.requiredQuantity) {
                    val multiCount = purchasedPacks / prom.requiredQuantity
                    val remainder = purchasedPacks % prom.requiredQuantity
                    (prom.totalPrice * multiCount) + (price * remainder)
                } else price * purchasedPacks
            }
        }
    }
}

@Serializable
data class RetailProductWithOffer(
    val product: RetailProduct,
    val offer: RetailOffer
)

@Serializable
data class RetailSourceMetadata(
    val retailerId: RetailerId,
    val sourceType: RetailOfferSourceType = RetailOfferSourceType.LIVE,
    val sourceReference: String,
    val observedAt: Long = System.currentTimeMillis(),
    val isLive: Boolean = true,
    val parseWarnings: List<String> = emptyList()
)

@Serializable
data class RetailerCapabilityDto(
    val retailerId: RetailerId,
    val mode: RetailerCapabilityMode,
    val searchSupported: Boolean,
    val livePriceSupported: Boolean,
    val availabilitySupported: Boolean,
    val promotionsSupported: Boolean,
    val description: String
)

@Serializable
data class RetailerCapabilitiesResponse(
    val capabilities: List<RetailerCapabilityDto>
)

@Serializable
sealed interface RetailProductSearchResult {
    @Serializable
    data class Success(
        val productsWithOffers: List<RetailProductWithOffer>,
        val metadata: RetailSourceMetadata
    ) : RetailProductSearchResult

    @Serializable
    data class PartialSuccess(
        val productsWithOffers: List<RetailProductWithOffer>,
        val warnings: List<String>,
        val metadata: RetailSourceMetadata
    ) : RetailProductSearchResult

    @Serializable
    data class Unavailable(
        val reason: String,
        val errorCode: String,
        val cachedFallbackAvailable: Boolean
    ) : RetailProductSearchResult
}

@Serializable
data class RetailFreshnessPolicy(
    val freshMaxAgeMs: Long = 24 * 60 * 60 * 1000L, // 24 hours
    val staleMaxAgeMs: Long = 72 * 60 * 60 * 1000L  // 72 hours
)

@Serializable
data class RemainderPenaltyConfig(
    val frozenPenaltyPerBaseUnit: Double = 0.05,
    val ambientPenaltyPerBaseUnit: Double = 0.10,
    val chilledPenaltyPerBaseUnit: Double = 0.50,
    val freshPenaltyPerBaseUnit: Double = 1.00
)

@Serializable
data class UserShoppingPreferences(
    val enabledRetailers: Set<RetailerId> = RetailerId.entries.toSet(),
    val avoidExtraStoreTrips: Boolean = true,
    val minimumSavingsForExtraStore: Money = Money(300L), // £3.00
    val preferFrozenVegetables: Boolean = true,
    val allowLargeFrozenValuePacks: Boolean = true,
    val enableAsdaRewards: Boolean = false,
    val enableIcelandBonusCard: Boolean = false,
    val additionalStorePenalty: Money = Money(200L), // £2.00 convenience penalty
    val preferredStores: List<RetailStoreLocation> = emptyList()
)

@Serializable
data class PackAlternative(
    val productWithOffer: RetailProductWithOffer,
    val packCountToBuy: Int,
    val totalCost: Money,
    val totalQuantityPurchased: Double,
    val unit: String,
    val requirementCovered: Double,
    val expectedRemainder: Double,
    val wastePenaltyScore: Double
)

@Serializable
data class BasketLine(
    val shoppingRequirementId: String,
    val requirementDisplayName: String,
    val retailerId: RetailerId,
    val productWithOffer: RetailProductWithOffer,
    val packCountToBuy: Int,
    val lineTotalCost: Money,
    val requirementCovered: Double,
    val expectedRemainder: Double,
    val matchConfidence: MatchConfidence
)

@Serializable
data class BasketPlan(
    val planId: String,
    val stores: Set<RetailerId>,
    val lines: List<BasketLine>,
    val feasibilityTier: BasketFeasibilityTier,
    val basketSubtotal: Money,
    val estimatedTripPenalty: Money,
    val estimatedWastePenaltyScore: Double,
    val unmatchedRequirements: List<String>,
    val uncertainItems: List<String>,
    val explanationText: String,
    val generatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class BasketComparisonResult(
    val selectedPlan: BasketPlan,
    val bestSingleStorePlan: BasketPlan? = null,
    val bestMultiStorePlan: BasketPlan? = null,
    val alternativePlans: List<BasketPlan> = emptyList(),
    val rawMultiStoreSaving: Money? = null,
    val minimumSavingsForExtraStore: Money,
    val extraStoreThresholdApplied: Boolean,
    val feasibilityTier: BasketFeasibilityTier,
    val unmatchedRequirements: List<String>,
    val uncertainItems: List<String>,
    val staleOfferCount: Int = 0,
    val unknownAvailabilityCount: Int = 0,
    val generatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class BasketDiffEvent(
    val type: BasketDiffEventType,
    val requirementDisplayName: String,
    val description: String
)

@Serializable
data class BasketPlanDiff(
    val oldPlanId: String,
    val newPlanId: String,
    val events: List<BasketDiffEvent>,
    val subtotalDifference: Money
)
