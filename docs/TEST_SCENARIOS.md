# Test Scenarios & Invariant Verification Matrix

This document outlines the mandatory unit, integration, and rule tests required by the system specification.

---

## Test Verification Matrix

| Test ID | Scenario Description | Expected Outcome / Invariant | Status |
|---|---|---|---|
| **TEST-01** | Planning a meal does not deduct inventory. | Creating or updating a meal in `PLANNED` status leaves `InventoryItem.quantity` unchanged and creates zero `InventoryTransaction` records. | VERIFIED |
| **TEST-02** | Marking cooked deducts inventory exactly once (Idempotency). | Invoking `deductQuantityForMeal()` checks `TransactionDao.hasCookedTransactionForMeal(mealId)`. Duplicate executions return `DomainError.DuplicateOperation`. | VERIFIED |
| **TEST-03** | OUT/DISCARDED ingredient excluded from usable inventory. | `observeUsableInventory()` filters out items with `status = OUT` or `DISCARDED`. | VERIFIED |
| **TEST-04** | User correction overrides older state. | Explicit user correction ("No mushrooms left") immediately updates `status = OUT` and overrides older `AVAILABLE` records. | VERIFIED |
| **TEST-05** | Quantity safety: Cannot become negative. | Deduction request exceeding available EXACT quantity returns `DomainError.InsufficientQuantity` and inventory remains unchanged. | VERIFIED |
| **TEST-06** | Whole pack / tin count reduction. | Deducting 1 tin from a 3-tin pack reduces `packCount` to 2 and `quantity` by 405g without corrupting pack metadata. | VERIFIED |
| **TEST-07** | Deterministic unit conversions (kg <-> g, L <-> ml). | UnitConverter converts 1.5kg to 1500g and 2L to 2000ml; incompatible conversions (g -> ml) return `DomainError.InvalidUnitConversion`. | VERIFIED |
| **TEST-08** | Restocking OUT item restores AVAILABLE status. | Restocking an item with `status = OUT` sets `status = AVAILABLE` and logs `RESTOCK` transaction. | VERIFIED |
| **TEST-09** | Discarding food logs audit trail and excludes from usable queries. | Discarding food sets `status = DISCARDED`, `quantity = 0.0`, logs `DISCARDED` transaction, and excludes from usable inventory queries. | VERIFIED |
| **TEST-10** | Unknown quantity is never converted into fake exact weight. | `quantityConfidence = UNKNOWN` or `quantity = null` persists as null/unknown without converting into synthetic gram weights. | VERIFIED |
