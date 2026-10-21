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

The current client fetches Iceland search/category HTML. The parser extracts product prices and product-level availability from JSON-LD when present. It does not currently call a separately identified JSON product/search endpoint.

- Offers remain ONLINE.
- InStock / OutOfStock only describes the scope represented by the structured offer; it is not branch stock.
- A page-wide or generic basket button is not evidence that each listed product is in stock.
- Missing, malformed, or conflicting source data should remain unknown or produce a parse warning rather than being guessed.

A future improvement is to inspect the live page's network requests and prefer its actual product/search JSON response where stable, while retaining HTML/JSON-LD as a fallback.

## Preferred branch matching

A branch preference is a user preference, not proof that a source response is scoped to that branch. The contract separates an app-local preference ID from optional retailer-issued branch identity. The current ASDA and Iceland parsers do not populate branch-specific offer IDs, so neither can currently produce verified branch-level prices or stock.

A branch-specific offer should only be considered branch evidence when the source response establishes:
1. A stable retailer branch ID matching the selected branch.
2. A product-level price and/or availability record scoped to that branch.
3. A STORE_SPECIFIC or IN_STORE price scope.
4. A source reference and observation timestamp adequate for freshness checks.

Never manufacture a branch ID from a name, postcode, store format, or local UUID. Online price evidence may still be useful for comparison but must not be shown as confirmed in-store price or stock.

## Next technical step

Trace Iceland's current website requests to identify its actual product/search API response and store-selection mechanism. Then add a source client and parser for that response with fixture-based tests. Only populate branch-specific offer fields when the request/response demonstrably includes the selected store and product-level price/availability. Keep the current HTML parser and cached/manual fallback if the endpoint is unavailable or changes.
