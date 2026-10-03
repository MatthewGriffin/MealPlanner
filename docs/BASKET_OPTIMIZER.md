# Basket Optimizer & Pack Selection Specification

This document defines the deterministic objective function, match precedence rules, and waste penalty weighting in Pantry Planner.

---

## 1. Match Precedence Rules (`RetailProductMatcher`)

1. User-confirmed `RetailProductMapping` (`CONFIRMED_MATCH` vs `REJECTED_MATCH`)
2. Exact Barcode Match
3. Exact `canonicalIngredientId`
4. Deterministic structured category match
5. Text match requiring confirmation (`TEXT_MATCH_REQUIRES_CONFIRMATION`)
6. Incompatible

*Distinct Food Protection*: Prevents cross-matching "Baked Beans with Sausages" when "Baked Beans" is required. Low-confidence / text matches **never** enter automated committed baskets without explicit user confirmation.

---

## 2. Waste & Remainder Penalty Weighting

Waste penalty score is weighted by storage class longevity:
- `FROZEN`: Penalty factor = 0.05 (long shelf life; remainder is useful stock).
- `AMBIENT`: Penalty factor = 0.10.
- `CHILLED`: Penalty factor = 0.50.
- `FRESH` (short-life): Penalty factor = 1.00 (high waste risk).
