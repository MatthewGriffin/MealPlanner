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

## Paginated catalogue collection (first implementation)

The backend exposes `GET /v1/retail/catalogue/iceland?page=0&pageSize=100` as a bounded, page-at-a-time catalogue import path. The response includes the requested page, `totalPages`, parsed products with observed prices, and a note that prices are online catalogue evidence rather than physical-branch prices or stock. Page indexes are zero-based and page sizes are limited to 100. A collector should read page 0, then request pages sequentially up to `totalPages - 1`, deduplicate by retailer product ID, and publish a new cache only after the retailer's crawl has completed successfully. If any page fails, retain the previous cache and retry later.

The endpoint uses browser-side settings discovered from Iceland's own homepage and same-site JavaScript; it does not guess credentials. Live verification on 2026-10-23 confirmed that the public Algolia request succeeds with the retailer storefront referer/origin, that a broccoli search returns parseable products, and that the empty-query catalogue response reports 20 pages with 100 parsed products on page 0. A request for page 1 also returned hits and the expected page index. The live smoke test has now been extended to crawl every advertised page, verify stable pagination metadata, parse products on each page, and report raw hits, parsed offers, and unique product IDs; that full-crawl test still needs to be run before claiming the entire catalogue has been verified end to end. The endpoint currently supports Iceland only; other retailers need their own source-specific bulk/pagination capability before they can be treated as full-catalogue sources.

The Android app now has a background importer for this endpoint. It reads pages sequentially (100 products per page), deduplicates by Iceland product ID, and writes the accumulated products to the existing retail cache only after the crawl finishes. It checks the last successful refresh on app launch, retries failures through WorkManager, and schedules a weekly refresh after a successful or still-fresh check. This does not block meal generation. A source-format change, unavailable index, inconsistent pagination, or suspiciously small result set causes a retry without publishing the incomplete crawl. The completed crawl replaces Iceland's product and price-snapshot rows in a single Room transaction, removing delisted products while retaining the prior catalogue if the transaction fails. The app also bundles a small first-launch starter cache (19 common products) with online prices observed on 2026-10-23, checked against Iceland's public [Frozen Vegetables & Rice](https://www.iceland.co.uk/frozen/frozen-vegetables-and-rice), [Frozen Essentials](https://www.iceland.co.uk/frozen/frozen-essentials), [Fruit & Veg](https://www.iceland.co.uk/fruit-and-veg), and [£2 Value](https://www.iceland.co.uk/2-value) category pages. It is seeded by a separate WorkManager task with no network requirement, so these initial estimates can be available offline; the following network-constrained refresh runs afterwards. Starter IDs are local placeholders, not Iceland product IDs. Prices and product selection are deliberately partial, availability is UNKNOWN, and the dataset is not a full catalogue. A successful complete live crawl replaces the starter rows atomically. Until a real crawl confirms the empty-query index and page counts, this must not be described as a verified full catalogue.

## ALDI: public product search

The backend queries ALDI's public search route (`https://www.aldi.co.uk/results?q=...`) and parses structured Product / ItemList JSON-LD when present. This source is retailer-wide: it does not accept a selected branch and is not evidence of stock in a particular store. Offers use `RETAILER_GENERAL` price scope, and availability is forced to `UNKNOWN` even if generic structured data includes an availability field. Promotions are not inferred from page-wide text. The result is marked partial success with warnings that prices may differ in store and the stock checker is not integrated. If the live page has no parseable structured offers, the adapter returns a structured unavailable result so cached/manual fallback remains possible.

Live verification attempted on 2026-10-24 did not produce parseable catalogue offers: the backend received an HTTP-success response but returned `SOURCE_FORMAT_CHANGED`, while a separate Python request from the same development environment received HTTP 403. This does not establish whether the page markup has changed or the source is applying request filtering; no product data should be inferred from the response. Keep cached/manual fallback available and treat ALDI live search as unverified until a permitted, stable response can be inspected. Do not attempt to bypass access controls. The ALDI stock checker is an estimate and is not treated as branch-level evidence here.

## Tesco: public grocery search

The backend queries Tesco's public grocery search route (`https://www.tesco.com/groceries/en-GB/search?query=...`) and parses structured Product / ItemList JSON-LD when present. Offers use `ONLINE` scope because Tesco describes online prices as guide prices that can differ from in-store prices. The search request does not establish the user's selected branch, and structured availability is ignored so the adapter always leaves stock `UNKNOWN`. Page-wide promotion inference is disabled because a promotion elsewhere on a search page is not proof that each product has that offer. If no structured offers parse, the adapter returns a structured unavailable result and keeps cached/manual fallback available.

The adapter has fixture-level tests, but production HTML parsing has not yet been verified with a live backend run. Tesco's app offers store-specific stock checking; this public website integration does not reproduce that feature.

## Morrisons, Sainsbury's, and Lidl: public catalogue searches

The additional retailer adapter uses public search sources for Morrisons, Sainsbury's, and Lidl. Morrisons and Lidl are parsed from structured Product / ItemList JSON-LD when present. Sainsbury's uses its public unauthenticated product-search response (/groceries-api/gol-services/product/v1/product) and maps only product ID, name, retail price, and image; it deliberately ignores the response's generic in_stock flag because it does not prove stock at the selected physical branch. Morrisons and Sainsbury's results are labelled ONLINE; Lidl results are labelled RETAILER_GENERAL because its public website is not a complete online grocery catalogue. The client uses ordinary public HTML requests with a modest per-client throttle and does not call undocumented private JSON APIs.

These routes and parsers are best-effort, not yet verified by a live backend run. The Sainsbury's product-search endpoint is a public website endpoint rather than a documented partner API and may change without notice. If a page changes, blocks requests, or contains no supported structured product records, the adapter returns SOURCE_UNAVAILABLE or SOURCE_FORMAT_CHANGED and preserves cached/manual fallback. Availability is forced to UNKNOWN and page-wide promotions are disabled for all three. In particular, Sainsbury's official store stock checker is not integrated into this product-price adapter; the fact that a checker exists does not establish an accessible supported endpoint.

## Sainsbury's paged catalogue endpoint (experimental)

The backend now exposes `GET /v1/retail/catalogue/sainsburys?page=1&pageSize=60` as a bounded, page-at-a-time probe of the public product endpoint. Page numbers are one-based and page size is limited to 60; the endpoint refuses unsupported or missing total-count metadata, counts above 9,900, missing product arrays, and empty pages when the source advertises products. It returns the source count/page count and a warning that a single page is not a complete catalogue. A collector must fetch every advertised page and verify the count remains stable before publishing a replacement cache. If requests are blocked or metadata changes, keep the prior cache and retry.

This is deliberately **not yet a verified full-catalogue integration**. The source endpoint is not a documented partner API and some automated clients may receive HTTP 403. The route and parser have fixture-level coverage only; a live crawl and full-catalogue completion test are still required before adding a Sainsbury's background cache importer or claiming full coverage. Prices are ONLINE and availability remains UNKNOWN regardless of generic `in_stock` fields.

## ASDA: gated pending source verification

The repository contains an experimental Algolia client and parser, but the endpoint is undocumented and requires a key whose provenance and permitted use have not been established. The adapter therefore does not invoke that client and reports MANUAL_AND_CACHED_ONLY. This is intentional: an endpoint being discoverable on a website is not by itself proof that automated use is permitted.

- Cached and manually entered offers remain available as fallback.
- The adapter reports SOURCE_PERMISSION_REQUIRED for non-empty live searches.
- No ASDA price or stock should be represented as live until a verified source is approved and enabled.
- When an approved source becomes available, online catalogue prices must remain labelled ONLINE and availability must remain UNKNOWN unless the response explicitly provides product-level status.

## Iceland: website search and structured product data

The client uses Iceland's public browser-side Algolia product index before falling back to the retailer's search HTML/JSON-LD. Live verification on 2026-10-23 confirmed the discovered application ID, search-only key and derived default product index work for a broccoli search and empty-query catalogue requests when the storefront referer/origin are supplied. A representative hit includes an Iceland product ID, name, GBP price object, and product-level in-stock flag; the parser handles those fields. The client looks for public browser-side settings on Iceland's homepage and up to 12 same-site JavaScript assets; it does not reuse ASDA credentials or guess missing settings. A discovered configuration is cached for the lifetime of the client, and GET/POST requests share the minimum-interval throttle.

- Offers remain ONLINE; a search hit alone does not establish physical-branch stock.
- Availability is populated only when a product hit has explicit product-level availability; otherwise it remains UNKNOWN.
- If configuration cannot be discovered, the API request fails, or no hits parse, the existing HTML/JSON-LD path remains the fallback.
- A page-wide or generic basket button is not evidence that each listed product is in stock.
- The optional live smoke tests have verified the search route, page 0 parsing, and page 1 retrieval. A new opt-in test now crawls every page and checks consistent pagination and parseable products; run it before treating the full catalogue crawl as live-verified.
- This remains a best-effort integration against public website configuration, not a documented or guaranteed Iceland API. The Android worker crawls pages sequentially and only publishes after the complete crawl succeeds; persistence is atomic and retains receipt products.

## Preferred branch matching

A branch preference is a user preference, not proof that a source response is scoped to that branch. The contract separates an app-local preference ID from optional retailer-issued branch identity. The current offer parsers do not populate branch-specific offer IDs, so the app cannot currently produce verified branch-level prices or stock from live search. ALDI's public catalogue adapter deliberately supplies retailer-general prices only, and Tesco's adapter supplies online guide prices only; both leave availability unknown.

A branch-specific offer should only be considered branch evidence when the source response establishes:
1. A stable retailer branch ID matching the selected branch.
2. A product-level price and/or availability record scoped to that branch.
3. A STORE_SPECIFIC or IN_STORE price scope.
4. A source reference and observation timestamp adequate for freshness checks.

Never manufacture a branch ID from a name, postcode, store format, or local UUID. Online price evidence may still be useful for comparison but must not be shown as confirmed in-store price or stock.

## Parser hardening completed

The shared structured-data parser now accepts JSON-LD product records expressed as a single object, an array, an `@graph`, or an `ItemList` with wrapped products. Repeated records with the same retailer product ID are deduplicated. Decimal prices are converted to minor currency units using decimal arithmetic and half-up rounding, avoiding floating-point truncation (for example, £1.99 becoming 199 pence). Fixture tests cover these cases, but the tests still need to be executed in the project build.

## Next technical step

Run the complete Android/backend build and tests, then verify each public search route against live HTML and save representative sanitized fixtures. Investigate branch-specific stock separately; where an official checker is interactive and no supported endpoint is available, retain UNKNOWN rather than reverse-engineering private endpoints or inferring stock. For every retailer, retain retailer-wide results as a fallback when branch-level evidence is unavailable, and keep the scope and freshness explicit. Do not turn unknown availability into in-stock.
