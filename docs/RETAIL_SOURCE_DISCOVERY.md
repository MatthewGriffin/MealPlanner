# Retail Source Discovery and Integration Status

This document records what the current website-backed integrations actually establish. Catalogue prices and online availability are not physical-branch prices or stock.

## Current integration matrix

| Retailer | Live retrieval | Price evidence | Availability | Physical branch support |
|---|---|---|---|---|
| **ASDA** | Disabled pending a verified approved source | Cached/manual evidence only while disabled | Unknown unless explicit saved evidence exists | Not implemented |
| **Iceland** | Best-effort public website search HTML / JSON-LD with an experimental browser-config discovery path | Parsed catalogue offers are labelled online | Only explicit product-level source status; not branch stock | Not implemented; no branch-scoped offer ID |
| **The Food Warehouse** | No independent live search | Cached/manual evidence only | Unknown unless explicit saved evidence exists | Not implemented |
| **Tesco, Morrisons, Sainsbury's, Aldi, Lidl** | No live source currently integrated | Cached/manual evidence only | Unknown unless explicit source evidence exists | Not implemented |

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

A branch preference is a user preference, not proof that a source response is scoped to that branch. The contract separates an app-local preference ID from optional retailer-issued branch identity. The current offer parsers do not populate branch-specific offer IDs, so the app cannot currently produce verified branch-level prices or stock from live search.

A branch-specific offer should only be considered branch evidence when the source response establishes:
1. A stable retailer branch ID matching the selected branch.
2. A product-level price and/or availability record scoped to that branch.
3. A STORE_SPECIFIC or IN_STORE price scope.
4. A source reference and observation timestamp adequate for freshness checks.

Never manufacture a branch ID from a name, postcode, store format, or local UUID. Online price evidence may still be useful for comparison but must not be shown as confirmed in-store price or stock.

## Next technical step

Integrate the first retailer catalogue source that can be accessed reliably and permissibly without bypassing access controls, then investigate branch-specific stock separately. For every retailer, retain retailer-wide results as a fallback when branch-level evidence is unavailable, and keep the scope and freshness explicit. Do not turn unknown availability into in-stock.
