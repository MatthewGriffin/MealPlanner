# Retail Source Discovery Audit & Capability Matrix

This document defines the capability mode, search support, availability mapping, and rate-limiting policy for each supported retailer in Pantry Planner.

---

## 1. Retailer Capability Matrix

| Retailer | Capability Mode | Search | Live Price | Availability | Rate Limit / Policy | Implementation Strategy |
|---|---|---|---|---|---|---|
| **Iceland** | `LIVE_SEARCH_AVAILABLE` | Supported | Supported | Evidence-based (`IN_STOCK`, `OUT_OF_STOCK`, `UNKNOWN`) | 1 req/sec (60/min) | **Live Adapter Implemented**: Public HTML / JSON-LD microdata parser + offline test resource fixtures. |
| **Asda** | `MANUAL_AND_CACHED_ONLY` | Unsupported (CSR/Anti-bot) | Advisory | `UNKNOWN` | High 403 risk | **Marked Unsupported for Live Search**: To respect site access terms, automated search is disabled. Uses local cache / fixtures or manual URL refresh. |
| **Food Warehouse** | `SHARED_CATALOGUE_WITH_ICELAND` | Shared | Shared | `UNKNOWN` | Shared with Iceland | **Shared Catalogue**: Shares Iceland catalog infrastructure; does not create fake Food Warehouse store prices without explicit store evidence. |

---

## 2. Evidence-Based Availability Mapping Rule

- **Explicit `"Out of Stock"` / `"Unavailable"` badge**: Mapped to `RetailAvailability.OUT_OF_STOCK`.
- **Explicit `"InStock"` JSON-LD / enabled `"add-to-basket"` button**: Mapped to `RetailAvailability.IN_STOCK`.
- **Absence of explicit stock evidence**: Defaults safely to `RetailAvailability.UNKNOWN`.
