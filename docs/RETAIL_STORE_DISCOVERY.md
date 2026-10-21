# Retail Store Discovery

## First implementation: OpenStreetMap directory search

The backend exposes `POST /v1/retail/stores/search` to discover branches for one selected retailer. Search accepts a UK postcode or latitude/longitude and a radius between 500 m and 15 km. Postcodes are geocoded with Nominatim; nearby records are queried from Overpass.

This is a **store directory**, not a price or stock API. Each result carries an OpenStreetMap `directorySourceId` (for example `node/123` or `way/456`) separately from `RetailStoreLocation.retailerStoreId`. Never copy an OSM ID into the retailer-issued ID field or infer branch-level prices/stock from a discovered location.

## Reliability and privacy

- Results are cached in memory for six hours; the cache is bounded.
- Nominatim and Overpass requests are throttled per service instance. This is best-effort courtesy throttling, not a substitute for a production proxy/cache policy at scale.
- Overpass queries are sent as HTTP POST with the query in the URL-encoded `data` form field. If a user saves an Overpass.ch key, the keyed endpoint is tried first, followed by public instances if it fails. and use one bounded nearby-shop scan; the app filters returned records to the selected retailer locally. Searches try the primary public endpoint, then two alternate public instances if a request fails. These are community-operated services, so all can still be slow or unavailable; users can retry or add a branch manually.
- Only public OSM tags matching the selected retailer are returned. Coverage and brand tags can be missing or stale; users should verify a result before saving it.
- Postcode search is UK-only. The backend does not request device GPS permission; coordinates from a future UI should be sent only after the user chooses nearby search.
- The app must show attribution to OpenStreetMap contributors and link to the OSM copyright page.
- OSM data is available under the Open Database License (ODbL); review attribution and database licensing obligations before redistributing derived datasets.

Provide either `postcode` or both coordinates, plus `retailerId`. Radius defaults to 5000 metres. The endpoint does not return a retailer-issued branch ID. Official retailer directories can be added later behind the same provider contract where permitted.
