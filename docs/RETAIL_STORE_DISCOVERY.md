# Retail Store Discovery

## First implementation: OpenStreetMap directory search

The backend exposes `POST /v1/retail/stores/search` to discover branches for one selected retailer. Search accepts a UK postcode or latitude/longitude and a radius between 500 m and 15 km. Postcodes are geocoded with Nominatim; nearby records are queried from Overpass.

This is a **store directory**, not a price or stock API. Each result carries an OpenStreetMap `directorySourceId` (for example `node/123` or `way/456`) separately from `RetailStoreLocation.retailerStoreId`. Never copy an OSM ID into the retailer-issued ID field or infer branch-level prices/stock from a discovered location.

## Reliability and privacy

- Results are cached in memory for six hours; the cache is bounded.
- Nominatim requests are throttled per service instance. Overpass requests are spaced at least 2.1 seconds apart to stay below Overpass.ch's published 30 requests/minute limit; this is best-effort local throttling, not a substitute for a production proxy/cache policy at scale.
- Overpass queries are sent as HTTP POST with the query in the URL-encoded `data` form field. If a user saves an Overpass.ch key, the keyed endpoint is tried first, followed by public instances if it fails. The query uses one bounded nearby-shop scan; the app filters returned records to the selected retailer locally.
- If every Overpass instance fails, the provider tries a bounded Nominatim search for the selected retailer near the resolved coordinates. For an all-retailer search, it also supplements the shared map results with targeted Nominatim searches for **every supported retailer**, even when that retailer already has one or more map results. This helps recover branches omitted by the bounded map query and smaller formats such as Tesco Express and Morrisons Daily. Morrisons discovery also searches the legacy “McColl's” name and normalizes matching results to Morrisons Daily, because some map records may retain the acquired chain's older name. These are still OpenStreetMap-derived search results, not official retailer-directory data; coverage is not guaranteed.
- Fallback branch names and postcodes are taken only from returned OSM listing/address fields; the user's search postcode is never copied into a branch result. Fallback results are labelled as less certain and should be checked before saving. If Nominatim also returns no usable listing, the app reports failure and leaves the manual branch form untouched.
- Official retailer directory adapters are not yet integrated. Adding them safely requires verifying a stable, permitted source for each retailer rather than assuming their public store-locator pages expose a supported API.
- Only public OSM tags matching the selected retailer are returned. Coverage and brand tags can be missing or stale; users should verify a result before saving it. Saved preferred branches also determine which retailers participate in basket comparisons.
- Postcode search is UK-only. The backend does not request device GPS permission; coordinates from a future UI should be sent only after the user chooses nearby search.
- The app must show attribution to OpenStreetMap contributors and link to the OSM copyright page.
- OSM data is available under the Open Database License (ODbL); review attribution and database licensing obligations before redistributing derived datasets.

Provide either `postcode` or both coordinates, plus `retailerId`. Radius defaults to 5000 metres. The endpoint does not return a retailer-issued branch ID. Official retailer directories can be added later behind the same provider contract where permitted.
