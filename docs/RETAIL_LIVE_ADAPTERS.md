# Phase 7B: Retailer Adapter Architecture & Parser Precedence

This document specifies the Ktor backend retailer stack, JSON-LD vs. HTML parser precedence, Money price parsing, and pack size normalization.

---

## 1. Parser Precedence Rules (`IcelandProductParser`)

1. Valid structured Schema.org JSON-LD microdata for explicit fields.
2. Explicit product card HTML DOM attributes.
3. Narrow HTML text fallback parsing.
4. `UNKNOWN` / absent.

*Conflict Warning*: If JSON-LD price and HTML DOM price conflict, a parse conflict warning is emitted, and structured JSON-LD price is preserved.

---

## 2. Rate Limiting Policy

- `ICELAND_MIN_REQUEST_INTERVAL_MS` = `1000L` (1 request per second).
- `ICELAND_MAX_REQUESTS_PER_MINUTE` = `60`.
- User-Agent header: `PantryPlannerRetailAdapter/1.0`.
- Documented as Pantry Planner internal request policy (not retailer permission).


## 3. Source permission and branch-scope boundary

- ASDA automated retrieval is gated in `AsdaRetailerAdapter` until explicit permission or an approved API/feed is available. The existing undocumented Algolia client/parser are not invoked by the adapter.
- Iceland's current parser returns online-scope offers only. It does not provide a retailer-issued branch ID, and page-wide basket controls are not used as product-level stock evidence.
- `RetailStoreLocation.id` is an app-local preference ID. Branch offers may match only `RetailStoreLocation.retailerStoreId`, populated from an authorised retailer directory/feed; if it is absent, branch-scoped offers are excluded for that preferred retailer.
- Do not promote `ONLINE` offers to `STORE_SPECIFIC` or `IN_STORE` based on postcode, store name, format, or a selected preference. Branch-level integration requires a documented/authorised source with stable branch IDs and product-level scoped prices/availability.
- See [RETAIL_SOURCE_DISCOVERY.md](RETAIL_SOURCE_DISCOVERY.md) for the source audit and retailer-specific limitations.
