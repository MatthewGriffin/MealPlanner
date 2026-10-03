# Phase 7A: Retail Product Matching & Basket Optimization Architecture

This document specifies the retailer product contracts, exact Money representation, product matching precedence, pack selection arithmetic, and basket optimization rules in Pantry Planner.

---

## 1. Core Principles

1. **Responsibility Separation**:
   - `ShoppingDomainService`: Determines required ingredient quantities.
   - Retailer layer (`RetailRepository` / `RetailerAdapter`): Provides candidate product, price, and availability facts.
   - `BasketOptimizer`: Evaluates whole store baskets and selects optimal store combinations.
2. **Exact Money Representation**:
   - Floating-point `Double` is strictly forbidden for money arithmetic.
   - All prices, subtotals, savings, and penalties use `Money(val amountMinor: Long, val currencyCode: String = "GBP")` (e.g., 138L = £1.38).
3. **Separation of Stable Metadata and Offers**:
   - `RetailProduct`: Stable metadata (name, pack size, storage class). `packCount` defaults to `null` (never defaults unknown packs to 1).
   - `RetailOffer`: Time-varying offer data (`price`, `promotion`, `availability`, `observedAt`). `availability` defaults to `UNKNOWN`.
4. **Feasibility Tiers**:
   - `COMPLETE` outranks `PARTIAL` regardless of monetary cost.
5. **Second-Store Minimum Savings Threshold**:
   - Evaluates best single-store basket cost vs. split-store basket cost.
   - If split savings $< \text{minimumSavingsForExtraStore}$ (e.g. £3.00), the optimizer selects the best single-store plan immediately and explains why.
