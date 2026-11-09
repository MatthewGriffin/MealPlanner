# Retail AI Tools and Local Purchase Price History

Live retailer catalogue scraping, online price lookup, branch discovery for price comparison, and retailer-specific stock lookup have been retired from the active shopping flow.

## Current behaviour

- Users add their own shop names; no retailer presets or store addresses are required.
- Actual prices can be recorded manually, during barcode product confirmation, or by linking a receipt line to an inventory or shopping-list item when saving a receipt. Receipts add a shop automatically if none matches the retailer.
- Price observations are stored locally in Room and retain the purchase date, product identity, pack details, and price paid.
- The shopping screen matches recorded prices to an item by exact product concept or exact name. These are historical observations, not live quotes.
- The best shop is the lowest total spend that buys enough whole packs to cover the amount needed. Price per unit breaks ties, then the most recent price. An option with an unknown pack size or an incompatible unit is listed but can't be best.
- Missing prices remain unknown. The app must not invent a price, availability, promotion, or saving.
- Shop IDs are stable when a shop is renamed. Removing a shop archives it so historical price records remain intact.
- The initial implementation does not upload or share purchase data. A future shared database can be added separately.

## Deprecated AI tool identifiers

The legacy `GET_RETAIL_BASKET_COMPARISON`, `GET_RETAIL_ITEM_OPTIONS`, and `SEARCH_RETAIL_PRODUCTS` tool identifiers remain for compatibility with existing tool contracts, but the provider no longer advertises them to the model. If called directly by an older client, their handlers return a clear message that live lookup is disabled and do not call retailer catalogue adapters.

## Future optimisation requirements

A future local optimiser should compare the latest recorded price per shop, account for price age and pack-size equivalence, and only recommend store splits when the savings justify an extra trip. It must distinguish exact product identity from merely similar names and leave unobserved prices unknown.
