# Meal Planner Rules & Domain Logic

This document specifies the deterministic business rules enforced by application logic and communicated to the AI reasoning engine.

---

## 1. Inventory Invariants & Safety Rules
- **Non-Negative Quantities**: Quantities can never become negative. Deductions exceeding available EXACT stock fail with `DomainError.InsufficientQuantity`.
- **Idempotent Deductions**: Deductions for a cooked meal check `TransactionDao.hasCookedTransactionForMeal(mealId)`. Duplicate deduction attempts are rejected cleanly (`DomainError.DuplicateOperation`).
- **User Correction Precedence**: Explicit user corrections (e.g., "No mushrooms left") immediately override older state, setting `status = OUT` and `quantity = 0.0`.
- **Database Authority**: Always query Room database state rather than relying on conversation memory.
- **State Precedence**: `OUT` and `DISCARDED` override previous `AVAILABLE` records.
- **Quantity Fidelity**: `UNKNOWN` quantities remain `null`; `APPROXIMATE` quantities remain `APPROXIMATE` unless an explicit exact quantity is provided.
- **Whole Pack Semantics**: Deducting unopened tins/packs reduces `packCount` without creating fractional unopened packages.

---

## 2. Perishable Scoring & Expiry Urgency
- **Priority**: High-risk perishables near use-by/best-before dates receive maximum meal suggestion score boost.
- **Urgency Hierarchy**:
  1. Opened chilled items
  2. Fresh meat/dairy nearing use-by
  3. Bakery nearing stale date
  4. Opened sauce/yoghurt
  5. Ambient cupboard / frozen items

---

## 3. Vegetables & Produce Rules
- **Frozen Vegetable Preference**: Prefer frozen vegetables over fresh by default.
- **Purchase Constraint**: Do NOT recommend buying fresh vegetables if an appropriate frozen equivalent is owned in inventory.

---

## 4. Garlic Rule
- Do NOT recommend buying fresh garlic simply because a recipe lists it if garlic powder is owned in inventory.

---

## 5. Meat & Protein Rules
- Fresh meat may be purchased early IF package use-by date covers planned cooking date.
- Match pack sizes to 2-person household needs (e.g., 400g pack preferred over 320g).

---

## 6. Beans & Canned Goods Rules
- Reheating beans: Use MICROWAVE. Stovetop pan cooking is used when beans are part of a sauce/curry/chilli.
- Keep baked beans, baked beans with sausages, chilli beans, and BBQ beans as distinct canonical items.

---

## 7. Repetitive Meal Penalties
- Lookback period: 14 days.
- Penalties:
  - Same exact dish within 7 days: Strong penalty (-20).
  - Same primary carbohydrate on consecutive days: Medium penalty (-15).
