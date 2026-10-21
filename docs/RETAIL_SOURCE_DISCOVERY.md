# Retail Source Discovery and Integration Status

This document records what the current website-backed integrations actually establish. Catalogue prices and online availability are not physical-branch prices or stock.

## Current integration matrix

| Retailer | Live retrieval | Price evidence | Availability | Physical branch support |
|---|---|---|---|---|
| **ASDA** | Website-backed Algolia product search | Parsed catalogue prices are labelled online | Unknown; parser does not claim stock | Not implemented; no verified retailer branch ID in parsed hits |
| **Iceland** | Website search HTML plus product JSON-LD | Structured product offer price, labelled online | Only explicit product-level JSON-LD status; this is online scope, not branch stock | Not implemented; current search-page parser has no branch-scoped offer ID |
| **The Food Warehouse** | Shared catalogue integration where available | Only evidence from the underlying catalogue | Unknown unless the underlying source explicitly states it | Iceland catalogue results must not be relabelled as Food Warehouse branch offers |
| **Tesco, Morrisons, Sainsbury's** | No live source currently integrated | Cached/manual evidence only | Unknown unless explicit source evidence exists | Not implemented |

## ASDA: website-backed Algolia search

The backend posts a search request to the Algolia endpoint used by the current integration and parses returned product hits. The API key is supplied through ASDA_ALGOLIA_API_KEY (or the existing system-property fallback); if it is missing, search returns a configuration error and callers can retain cached/manual fallback.

- Search results are treated as online catalogue offers.
- Product prices and promotions are parsed from returned hit fields.
- Availability remains UNKNOWN; search hits do not prove store stock.
- Query strings are JSON-serialized instead of interpolated into request JSON.
- If the API response changes or contains no parseable products, the adapter returns a structured unavailable result so cached/manual fallback remains possible.

The Algolia endpoint is website-backed and undocumented. Its continued availability, required credentials, and permitted use are not guaranteed. The integration should be resilient to HTTP errors and schema changes; it must not bypass authentication or access controls.

## Iceland: website search and structured product data

The client experimentally attempts a direct Algolia product-search request before falling back to the retailer's search HTML/JSON-LD. The search page itself is confirmed to exist, but the current available inspection has not exposed Iceland's live browser network traffic or a real Algolia response. Therefore, Iceland's current use of Algolia, the discovered configuration patterns, and the hit schema are not yet verified against production. The client looks for public browser-side application ID, search-only key, and index name on Iceland's homepage and up to 12 same-site JavaScript assets; it does not reuse ASDA credentials or guess missing settings. A discovered configuration is cached for the lifetime of the client, and GET/POST requests share the minimum-interval throttle.

- Offers remain ONLINE; a search hit alone does not establish physical-branch stock.
- Availability is populated only when a product hit has explicit product-level availability; otherwise it remains UNKNOWN.
- If configuration cannot be discovered, the API request fails, or no hits parse, the existing HTML/JSON-LD path remains the fallback.
- A page-wide or generic basket button is not evidence that each listed product is in stock.
- Missing, malformed, or conflicting source data should remain unknown or produce a parse warning rather than being guessed.
- Mocked tests prove the client request construction against a synthetic page fixture only; they do not prove the live site currently publishes those settings or accepts the request.
- This is a best-effort integration against public website configuration, not a documented or guaranteed Iceland API. A representative production response fixture and an end-to-end run are still required before calling the direct API path verified.

## Preferred branch matching

A branch preference is a user preference, not proof that a source response is scoped to that branch. The contract separates an app-local preference ID from optional retailer-issued branch identity. The current ASDA and Iceland parsers do not populate branch-specific offer IDs, so neither can currently produce verified branch-level prices or stock.

A branch-specific offer should only be considered branch evidence when the source response establishes:
1. A stable retailer branch ID matching the selected branch.
2. A product-level price and/or availability record scoped to that branch.
3. A STORE_SPECIFIC or IN_STORE price scope.
4. A source reference and observation timestamp adequate for freshness checks.

Never manufacture a branch ID from a name, postcode, store format, or local UUID. Online price evidence may still be useful for comparison but must not be shown as confirmed in-store price or stock.

## Next technical step

Run the new Iceland Algolia path against the live site and capture a representative response fixture to confirm current config patterns and hit-field names. Then trace store-selection requests separately. Only populate branch-specific offer fields when the request/response demonstrably includes the selected store and product-level price/availability. Keep the current HTML parser and cached/manual fallback if the endpoint is unavailable or changes.
