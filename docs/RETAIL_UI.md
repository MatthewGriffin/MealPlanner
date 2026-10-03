# Retail Comparison UI & Manual Input Specification

This document specifies the Shopping screen comparison flow, manual Asda / Food Warehouse offer entry, and one-time basket override vs permanent mapping confirmation.

---

## 1. UI Display Sections

- **Recommended Plan**: Subtotal, store list, line items, and explanation text.
- **Store Split Explanation**: Displays raw split saving vs user's configured minimum threshold.
- **Price Source Badges**: Visually distinguishes `LIVE ONLINE`, `CACHED FRESH`, `CACHED STALE`, `MANUAL`.

---

## 2. Override vs. Permanent Match Confirmation

- **One-time Basket Override** (`ONE_TIME_BASKET_OVERRIDE`): Applies specific candidate choice to current basket plan and triggers immediate deterministic basket recalculation without mutating canonical mappings.
- **Permanent Match Confirmation**: Explicit action (*"Remember this product match"*) creates `CONFIRMED_MATCH` in Room. *"Never use this"* creates `REJECTED_MATCH`.
