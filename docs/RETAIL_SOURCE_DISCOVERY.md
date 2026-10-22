# Retail Source Discovery and Integration Status

This document records what the current website-backed integrations actually establish. Catalogue prices and online availability are not physical-branch prices or stock.

## Current integration matrix

| Retailer | Live retrieval | Price evidence | Availability | Physical branch support |
|---|---|---|---|---|
| **ASDA** | Disabled pending a verified approved source | Cached/manual evidence only while disabled | Unknown unless explicit saved evidence exists | Not implemented |
| **Iceland** | Best-effort public website search HTML / JSON-LD with an experimental browser-config discovery path | Parsed catalogue offers are labelled online | Only explicit product-level source status; not branch stock | Not implemented; no branch-scoped offer ID |
| **The Food Warehouse** | No independent live search | Cached/manual evidence only | Unknown unless explicit saved evidence exists | Not implemented |
| **ALDI** | Public website search route `/results?q=...`, parsed from product JSON-LD when present | Retailer-general public catalogue price; may differ in store | Always UNKNOWN in this integration; no branch stock checker integration | Not implemented |
| **Tesco** | Public grocery search route, parsed from Product JSON-LD when present | Online guide prices; may vary by delivery area and in-store | Always UNKNOWN in this integration; branch stock not checked | Not implemented |
| **Morrisons** | Public online grocery search; structured product data when present | Online catalogue prices; delivery-area context may affect results | Always UNKNOWN in this integration | Not implemented |
| **Sainsbury's** | Public grocery search route; structured product data when present | Online catalogue prices; Nectar eligibility/context may affect effective prices | Always UNKNOWN in this integration; official stock checker remains separate | Not implemented |
| **Lidl** | Public website search for published products/offers | Retailer-general prices for a changing subset, not a full grocery catalogue | Always UNKNOWN in this integration | Not implemented |

## ALDI: public product search

The backend queries ALDI's public search route (`https://www.aldi.co.uk/results?q=...`) and parses structured Product / ItemList JSON-LD when present. This source is retailer-wide: it does not accept a selected branch and is not evidence of stock in a particular store. Offers use `RETAILER_GENERAL` price scope, and availability is forced to `UNKNOWN` even if generic structured data includes an availability field. Promotions are not inferred from page-wide text. The result is marked partial success with warnings that prices may differ in store and the stock checker is not integrated. If the live page has no parseable structured offers, the adapter returns a structured unavailable result so cached/manual fallback remains possible.

The parser has fixture-level coverage, but the live route and its current JSON-LD coverage have not yet been verified by running the backend against the production site. The ALDI stock checker is an estimate and is not treated as branch-level evidence here.

## Tesco: public grocery search

The backend queries Tesco's public grocery search route (`https://www.tesco.com/groceries/en-GB/search?query=...`) and parses structured Product / ItemList JSON-LD when present. Offers use `ONLINE` scope because Tesco describes online prices as guide prices that can differ from in-store prices. The search request does not establish the user's selected branch, and structured availability is ignored so the adapter always leaves stock `UNKNOWN`. Page-wide promotion inference is disabled because a promotion elsewhere on a search page is not proof that each product has that offer. If no structured offers parse, the adapter returns a structured unavailable result and keeps cached/manual fallback available.

The adapter has fixture-level tests, but production HTML parsing has not yet been verified with a live backend run. Tesco's app offers store-specific stock checking; this public website integration does not reproduce that feature.

## Morrisons, Sainsbury's, and Lidl: public catalogue searches

The additional retailer adapter uses public search sources for Morrisons, Sainsbury's, and Lidl. Morrisons and Lidl are parsed from structured Product / ItemList JSON-LD when present. Sainsbury's uses its public unauthenticated product-search response (/groceries-api/gol-services/product/v1/product) and maps only product ID, name, retail price, and image; it deliberately ignores the response's generic in_stock flag because it does not prove stock at the selected physical branch. Morrisons and Sainsbury's results are labelled ONLINE; Lidl results are labelled RETAILER_GENERAL because its public website is not a complete online grocery catalogue. The client uses ordinary public HTML requests with a modest per-client throttle and does not call undocumented private JSON APIs.

These routes and parsers are best-effort, not yet verified by a live backend run. The Sainsbury's product-search endpoint is a public website endpoint rather than a documented partner API and may change without notice. If a page changes, blocks requests, or contains no supported structured product records, the adapter returns SOURCE_UNAVAILABLE or SOURCE_FORMAT_CHANGED and preserves cached/manual fallback. Availability is forced to UNKNOWN and page-wide promotions are disabled for all three. In particular, Sainsbury's official store stock checker is not integrated into this product-price adapter; the fact that a checker exists does not establish an accessible supported endpoint.

## ASDA: gated pending source verification

The repository contains an experimental Algolia client and parser, but the endpoint is undocumented and requires a key whose provenance and permitted use have not been established. The adapter therefore does not invoke that client and reports MANUAL_AND_CACHED_ONLY. This is intentional: an endpoint being discoverable on a website is not by itself proof that automated use is permitted.

- Cached and manually entered offers remain available as fallback.
- The adapter reports SOURCE_PERMISSION_REQUIRED for non-empty live searches.
- No ASDA price or stock should be represented as live until a verified source is approved and enabled.
- When an approved source becomes available, online catalogue prices must remain labelled ONLINE and availability must remain UNKNOWN unless the response explicitly provides product-level status.

## Iceland: website search and structured product data

The client experimentally attempts a direct Algolia product-search request before falling back to the retailer's search HTML/JSON-LD. The search page itself is confirmed to exist, but the current available inspection has not exposed Iceland's live browser network traffic or a representative production Algolia response. Therefore, the current use of Algolia, discovered configuration patterns, and hit schema are not verified against production. The client looks for public browser-side application ID, search-only key, and index name on Iceland's homepage and up to 12 same-site JavaScript assets; it does not reuse ASDA credentials or guess missing settings. A discovered configuration is cached for the lifetime of the client, and GET/POST requests share the minimum-interval throttle.

- Offers remain ONLINE; a search hit alone does not establish physical-branch stock.
- Availability is populated only when a product hit has explicit product-level availability; otherwise it remains UNKNOWN.
- If configuration cannot be discovered, the API request fails, or no hits parse, the existing HTML/JSON-LD path remains the fallback.
- A page-wide or generic basket button is not evidence that each listed product is in stock.
- Mocked tests prove request construction against synthetic fixtures only; they do not prove the live site currently publishes those settings or accepts the request.
- This is a best-effort integration against public website configuration, not a documented or guaranteed Iceland API. A representative production response fixture and an end-to-end run are still required before calling the direct API path verified.

## Preferred branch matching

A branch preference is a user preference, not proof that a source response is scoped to that branch. The contract separates an app-local preference ID from optional retailer-issued branch identity. The current offer parsers do not populate branch-specific offer IDs, so the app cannot currently produce verified branch-level prices or stock from live search. ALDI's public catalogue adapter deliberately supplies retailer-general prices only, and Tesco's adapter supplies online guide prices only; both leave availability unknown.

A branch-specific offer should only be considered branch evidence when the source response establishes:
1. A stable retailer branch ID matching the selected branch.
2. A product-level price and/or availability record scoped to that branch.
3. A STORE_SPECIFIC or IN_STORE price scope.
4. A source reference and observation timestamp adequate for freshness checks.

Never manufacture a branch ID from a name, postcode, store format, or local UUID. Online price evidence may still be useful for comparison but must not be shown as confirmed in-store price or stock.

## Next technical step

Run the complete Android/backend build and tests, then verify each public search route against live HTML and save representative sanitized fixtures. Investigate branch-specific stock separately; where an official checker is interactive and no supported endpoint is available, retain UNKNOWN rather than reverse-engineering private endpoints or inferring stock. For every retailer, retain retailer-wide results as a fallback when branch-level evidence is unavailable, and keep the scope and freshness explicit. Do not turn unknown availability into in-stock.
