# Phase 7C: Retail AI Tools & Provenance Specification

This document defines the ai-contract-v2 READ-only retail tools, payload DTOs, system prompt v2 rules, and provenance qualification.

---

## 1. Canonical Retail AI Tools (`ai-contract-v2`)

1. **`GET_RETAIL_BASKET_COMPARISON`** (Class: `READ`):
   - Returns deterministic `BasketComparisonResult` (`selectedPlan`, `bestSingleStorePlan`, `bestMultiStorePlan`, `rawMultiStoreSaving`, `minimumSavingsForExtraStore`, `extraStoreThresholdApplied`, `feasibilityTier`, `staleOfferCount`, `unknownAvailabilityCount`).
2. **`GET_RETAIL_ITEM_OPTIONS`** (Class: `READ`):
   - Requires authoritative `shoppingListItemId`. Returns top 5 `PackAlternative` options per retailer with effective price, remainder, match confidence, and freshness.
3. **`SEARCH_RETAIL_PRODUCTS`** (Class: `READ`):
   - Non-authoritative search helper for general product queries (`query`, `retailerFilter`). Cannot mutate canonical mappings or create inventory stock.

---

## 2. System Prompt v2 Rules (`meal-planner-system-v2.txt`)

- Retail facts come strictly from retail tools.
- `BasketOptimizer` is authoritative for subtotals, store splits, and the user's configured extra-store minimum threshold.
- Price provenance and freshness qualification is required in prose (*"Based on an Iceland online price observed today..."*).
- Retail tools are 100% READ-only.
