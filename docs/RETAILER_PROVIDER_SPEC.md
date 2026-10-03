# Retailer Provider Specification & Price Optimization

This document specifies the abstraction interfaces, caching mechanisms, and provider implementations for grocery retail integrations.

---

## 1. Provider Abstraction (`RetailerProvider`)

```kotlin
interface RetailerProvider {
    val retailer: Retailer

    suspend fun searchProducts(query: String): Result<List<RetailProduct>>
    suspend fun lookupBarcode(barcode: String): Result<RetailProduct?>
    suspend fun lookupProduct(productId: String): Result<RetailProduct?>
    suspend fun getPrice(productId: String): Result<RetailPriceQuote?>
    suspend fun getAvailability(productId: String): Result<Boolean>
}
```

---

## 2. Initial Retailer Adapters
1. **Asda Provider (`AsdaRetailerProvider`)**: Handles product search and price quotes for Asda groceries.
2. **Food Warehouse Provider (`FoodWarehouseRetailerProvider`)**: Handles bulk frozen and grocery price lookup for Iceland / The Food Warehouse.

No Android UI or domain code directly references retailer API endpoints or HTML parsing. All data accesses pass through `RetailerProvider` implementations.

---

## 3. Caching & TTL Policy
To prevent unnecessary network calls and uphold offline-first principles, retailer data is cached in Room with strict Time-To-Live (TTL) limits:
- **Product Metadata** (title, brand, pack size, storage location): TTL 30 days.
- **Price Quotes** (unit price, package price): TTL 6 to 12 hours.
- **Store Availability**: TTL 1 to 4 hours.
