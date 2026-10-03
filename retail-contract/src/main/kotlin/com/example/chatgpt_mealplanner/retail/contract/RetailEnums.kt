package com.example.chatgpt_mealplanner.retail.contract

import kotlinx.serialization.Serializable

@Serializable
enum class RetailerId {
    ASDA,
    ICELAND,
    FOOD_WAREHOUSE
}

@Serializable
enum class RetailStorageClass {
    FROZEN,
    CHILLED,
    AMBIENT,
    FRESH,
    UNKNOWN
}

@Serializable
enum class RetailAvailability {
    IN_STOCK,
    OUT_OF_STOCK,
    LOW_STOCK,
    UNKNOWN
}

@Serializable
enum class PriceFreshness {
    FRESH,
    STALE,
    UNKNOWN
}

@Serializable
enum class RetailPriceScope {
    ONLINE,
    STORE_SPECIFIC,
    IN_STORE,
    RETAILER_GENERAL,
    UNKNOWN
}

@Serializable
enum class RetailPromotionScope {
    SAME_PRODUCT,
    ELIGIBLE_GROUP,
    UNKNOWN
}

@Serializable
enum class RetailOfferSourceType {
    LIVE,
    CACHE,
    RECEIPT,
    MANUAL,
    FIXTURE
}

@Serializable
enum class BasketDiffEventType {
    PRICE_CHANGED,
    PRODUCT_UNAVAILABLE,
    PRODUCT_REPLACED,
    PROMOTION_CHANGED,
    STORE_PLAN_CHANGED,
    COVERAGE_CHANGED
}

@Serializable
enum class RetailerCapabilityMode {
    LIVE_SEARCH_AVAILABLE,
    MANUAL_AND_CACHED_ONLY,
    SHARED_CATALOGUE_WITH_ICELAND,
    UNSUPPORTED
}

@Serializable
enum class MatchType {
    EXACT_CANONICAL,
    BARCODE_MATCH,
    CATEGORY_MATCH,
    TEXT_MATCH_REQUIRES_CONFIRMATION,
    INCOMPATIBLE
}

@Serializable
enum class MatchConfidence {
    EXACT,
    HIGH,
    MEDIUM,
    LOW,
    REQUIRES_CONFIRMATION
}

@Serializable
enum class RetailProductMappingDecision {
    CONFIRMED_MATCH,
    REJECTED_MATCH
}

@Serializable
enum class IngredientPurchaseConstraint {
    ANY,
    FRESH_REQUIRED,
    FROZEN_ALLOWED,
    CHILLED_REQUIRED
}

@Serializable
enum class BasketFeasibilityTier {
    COMPLETE,
    COMPLETE_WITH_UNCERTAINTY,
    PARTIAL
}
