# Phase 4: Deterministic Shopping List Generation Engine

This document specifies the deterministic business rules, mathematical formulas, staple restock logic, uncertainty safety models, and database migration policies implemented in Phase 4 of Pantry Planner.

---

## 1. Core Principles & System Invariants

1. **No AI Dependency**: Shopping calculations are 100% deterministic and database-authoritative.
2. **Read-Only List Generation**: Shopping list generation or regeneration reads usable inventory and planned meals; it **never** mutates or deducts raw inventory.
3. **No False Certainty**:
   - `UNKNOWN` or `NEEDS_CONFIRMATION` inventory lots do not erase known numeric stock.
   - Known stock is preserved while marking `quantityUncertain = true` and `requirementConfidence = NEEDS_CONFIRMATION`.
   - Staple top-ups are **never** calculated for uncertain inventory to prevent false overbuying.
4. **`mealShortfallQuantity` vs. `stapleReserveTopUpQuantity` Invariant**:
   For any combined meal demand + staple restock item, the following invariant strictly holds:
   $$\text{finalSuggestedPurchaseQuantity} = \text{mealShortfallQuantity} + \text{stapleReserveTopUpQuantity}$$
5. **Final Expected Stock Formula**:
   $$\text{expectedFinalInventory} = \max\left(0, \text{knownUsableQuantity} + \text{finalSuggestedPurchaseQuantity} - \text{plannedRequirement}\right)$$

---

## 2. Staple Restock Logic & Modes

Staple rules support two distinct modes (`StapleRuleMode`):

### Mode A: `QUANTITY`
- Restock triggers when post-plan expected inventory without purchase drops below threshold:
  $$\text{postPlanWithoutPurchase} = \max(0, \text{knownUsableQuantity} - \text{plannedRequirement}) < \text{thresholdQuantity}$$
- Purchase required to reach target post-plan:
  $$\text{purchaseNeededToReachTarget} = \max(0, \text{plannedRequirement} + \text{targetQuantity} - \text{knownUsableQuantity})$$
- Final suggested purchase:
  $$\text{finalSuggestedPurchaseQuantity} = \max(\text{mealShortfallQuantity}, \text{purchaseNeededToReachTarget})$$

### Mode B: `PACK_COUNT`
- Restock triggers when post-plan expected pack count drops below threshold:
  $$\text{postPlanPacks} = \max(0, \text{knownPacks} - \text{discretePacksNeeded}) \le \text{thresholdPackCount}$$
- Purchase required:
  $$\text{packsToBuy} = \max(0, \text{targetPackCount} - \text{postPlanPacks})$$

---

## 3. List Regeneration & Merge Rules

- **`MANUAL` Items**: Always survive regeneration.
- **`OPTIONAL_UPGRADE` Items**: Always survive regeneration.
- **`CHECKED` Item State Preservation**:
  - Matched by `canonicalIngredientId`.
  - If new requirement $\le$ old requirement and units/confidence match: **Preserve `CHECKED`**.
  - If new requirement $>$ old requirement (demand increased) or units change: **Reset to `NEEDED`**.
- **`PURCHASED_CONFIRMED` Handling**: Indicates physical inventory import. The next regeneration evaluates new physical inventory lots and naturally recalculates demand.

---

## 4. Room Migration `MIGRATION_2_3`

Room version upgraded from 2 to 3. Created tables:
1. `shopping_lists`
2. `shopping_list_items`
3. `shopping_requirement_sources`
4. `staple_rules`
