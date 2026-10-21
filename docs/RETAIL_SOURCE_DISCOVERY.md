# Retail Source Discovery Audit & Capability Matrix

This audit separates retailer-wide/online catalogue evidence from physical-branch prices and stock. A preferred branch is a user preference, not proof that a retailer's product feed is scoped to that branch.

## 1. Current capability matrix

| Retailer | Current mode | Online/catalogue price | Branch-specific price | Branch stock | Safe implementation |
|---|---|---|---|---|---|
| **Iceland** | Existing public product-page parser | Online-scope only when the page provides a price | Not available from the current search-page parser | Unknown unless the product's structured data explicitly reports availability; this is not branch stock | Keep offers at `ONLINE`, leave `storeLocationId` null, and never infer in-store stock from a generic page button |
| **ASDA** | `MANUAL_AND_CACHED_ONLY` | Automated retrieval disabled pending permission or an approved feed/API | Not available | Unknown | Cached or manually entered offers only; do not call the undocumented Algolia endpoint |
| **The Food Warehouse** | `SHARED_CATALOGUE_WITH_ICELAND` | Shared catalogue only where available | Not available | Unknown | Do not treat Iceland catalogue data as The Food Warehouse branch data |
| **Tesco, Morrisons, Sainsbury's** | No branch feed integrated | Depends on available cached/manual evidence | Not available | Unknown | Branch matching only when an authorised source supplies a stable branch ID and scoped offer |

## 2. Public-source findings

### ASDA

The [ASDA Groceries terms](https://www.asda.com/terms/grocery) state that use of the site is for browsing/buying and account management (clause 2.1.3), restrict commercial organisations' access (2.1.4), and say site data may not be used without ASDA's express permission (2.2.4). The app's previous Algolia endpoint was undocumented, and no approved public branch-level price/stock feed was identified during this audit.

Accordingly, the live adapter now reports `MANUAL_AND_CACHED_ONLY` and returns `SOURCE_PERMISSION_REQUIRED` without making a network request. Re-enable automated access only after explicit permission or an approved feed/API and its terms are recorded. This is a conservative engineering gate, not a legal determination.

### Iceland

The [Iceland terms](https://www.iceland.co.uk/terms.html) say offers can vary by store and between in-store and online (clause 10.3); the terms also limit copying/reuse of site content (clause 3.2). The current public HTML/JSON-LD parser supplies catalogue/online evidence, not a physical branch identifier. No documented public API/feed supplying stable branch IDs together with branch-level prices and stock was identified.

The current parser therefore keeps `priceScope = ONLINE` and `storeLocationId = null`. Availability is `UNKNOWN` unless that product's structured offer explicitly provides an availability value. A generic "add to basket" element elsewhere on a search page is not evidence that every listed product is available, and online availability is never evidence of in-store availability.

### The Food Warehouse

The [official Food Warehouse site](https://www.thefoodwarehouse.com/corporate/) describes its store finder, but a store locator by itself supplies location details—not a product-level branch price/stock feed. Shared Iceland catalogue records must not be relabelled as The Food Warehouse offers without source evidence.

## 3. Branch identity contract

A branch preference now has two separate identifiers:

- `id`: app-local ID for storing/editing the user's preference.
- `retailerStoreId`: optional stable ID supplied by an authorised retailer directory/feed.

Offers use `storeLocationId`, which must match `retailerStoreId`; it must never be matched against the app-local UUID. When a preferred branch has no retailer-issued ID, branch-scoped offers cannot be verified and are excluded for that retailer; online/general offers remain usable. When a branch ID exists, only offers scoped to that selected branch can be treated as branch evidence.

A branch offer is eligible only when the source provides all of the following:

1. A stable retailer branch ID that can be matched to the user's selected branch.
2. An explicit product-level price and/or availability record for that branch.
3. A scope identifying the data as `STORE_SPECIFIC` or `IN_STORE`.
4. A source reference and observation time sufficient to apply freshness rules.

Never manufacture a branch ID from a name, postcode, local UUID, or store format. A store locator alone cannot satisfy these requirements. A branch-specific price does not automatically establish stock, and a stock status does not automatically establish a price.

## 4. Evidence-based availability mapping

- Explicit product-level structured `InStock` evidence: may map to `IN_STOCK` for the scope represented by that source.
- Explicit product-level structured `OutOfStock` evidence: may map to `OUT_OF_STOCK` for the scope represented by that source.
- Missing, generic, conflicting, or page-wide evidence: `UNKNOWN`.
- Online-scope evidence must never be presented as physical-branch stock.

## 5. Next integration gate

The next useful step is to obtain retailer approval or an official partner/API feed that includes store IDs, scoped prices, and stock. Once available, add a source-specific branch directory lookup, persist the retailer-issued ID on the selected branch, and parse only documented branch-scoped responses. Until then, the app can compare online/general estimates and cached/manual data, but must continue to disclose that branch stock and branch-specific prices are unconfirmed.
