# Shopping List & Multi-Store Price Optimizer Specification

This document specifies the rules for generating grocery shopping lists and calculating optimal store assignments across multiple retailers.

---

## 1. Requirement Calculation
Shopping list requirements are calculated deterministically:

$$\text{Shopping Requirements} = (\text{Planned Recipe Ingredients} - \text{Usable Owned Inventory}) + \text{Low Staples} + \text{Recommended Quality Upgrades}$$

### Remainder & Waste Risk Evaluation
Each missing requirement generates a candidate purchase with remainder metadata:
- `requiredQuantity` vs `candidatePackSize`
- `expectedRemainder` = `candidatePackSize` - `requiredQuantity`
- `remainderStorageType`: `FROZEN`, `SHELF_STABLE`, or `PERISHABLE`
- `wasteRisk`:
  - `FROZEN` / `SHELF_STABLE`: LOW waste risk.
  - `PERISHABLE` with no second planned recipe: HIGH waste risk.

---

## 2. Multi-Store Basket Cost Formula
When evaluating store options (e.g. Asda vs. Iceland / The Food Warehouse), the optimizer calculates the **Effective Basket Cost**:

$$\text{Effective Cost} = \sum (\text{Item Prices}) + \text{Extra Store Visit Penalty} + \text{Expected Food Waste Penalty} + \text{Substitution Penalty}$$

### Parameters
- **Extra Store Visit Penalty**: Default £3.00 (configurable in user preferences).
- **Convenience Principle**: If splitting purchases across Asda and Food Warehouse saves less than the Extra Store Visit Penalty (e.g., £0.50 savings < £3.00 threshold), ALL items are assigned to a SINGLE primary store.
- If savings exceed the threshold (e.g., £5.20 savings > £3.00 threshold), the shopping list splits into two distinct store sections.

---

## 3. Shopping Scan-In & Accidental Purchase Handling
When the user returns from grocery shopping:
1. User enters the Shopping Scan-In screen.
2. User scans barcodes of items actually bought.
3. System logs `PURCHASE` transactions and creates/updates `InventoryItem` entries reflecting actual scans.
4. **Accidental Purchase Invariant**: If the plan requested 4 tins of normal baked beans, but the user actually scanned 3 tins of sausages & beans + 1 tin of normal baked beans, the inventory system records the exact items scanned (3 sausages & beans, 1 normal beans) without forcing expected product types.
