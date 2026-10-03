# Data Model & Room Schema Specification

This document details the database entities, relationships, enums, data invariants, and domain error abstractions for Pantry Planner.

---

## Architectural Decision: Physical Inventory Lot Model vs. Aggregated Ingredient Views

Every physical product purchase, manual entry, or barcode scan creates a distinct `InventoryItem` record in Room representing a **specific physical inventory lot/package**.

### Why Physical Lot Tracking?
- **Expiry & Opened State**: Enables separate `useByDate`, `openedDate`, `storageLocation`, and `status` for individual packages (e.g. 1 open cheese expiring tomorrow vs 1 sealed cheese expiring next month).
- **FIFO & Smart Consumption**: Deductions automatically target opened or earliest-expiring lots first.
- **Tin / Multi-Pack Semantics**: Unopened tins/packs remain unopened until individual consumption reduces `packCount`.

### Aggregated Views
Recipe planning and stock checks query aggregated usable inventory balances by summing quantities across all `AVAILABLE`, `LOW`, and `USE_SOON` lots grouped by `canonicalIngredientId` or `canonicalName`.

---

## Enums

```kotlin
enum class FoodCategory {
    CUPBOARD,
    FRIDGE,
    FREEZER,
    FRESH_PANTRY,
    BREAD_BAKERY,
    SAUCES_SEASONINGS,
    SNACKS,
    MEAL_KITS,
    OTHER
}

enum class StorageLocation {
    CUPBOARD,
    FRIDGE,
    FREEZER,
    FRESH_PANTRY,
    BREAD_BAKERY,
    SAUCES_SEASONINGS,
    SNACKS,
    MEAL_KITS,
    OTHER
}

enum class QuantityUnit {
    GRAM,
    KILOGRAM,
    MILLILITRE,
    LITRE,
    UNIT,
    PACK,
    TIN,
    BOTTLE,
    BAG,
    SLICE,
    PORTION
}

enum class QuantityConfidence {
    EXACT,
    APPROXIMATE,
    UNKNOWN
}

enum class ItemStatus {
    AVAILABLE,
    LOW,
    USE_SOON,
    OUT,
    DISCARDED,
    NEEDS_CONFIRMATION
}

enum class TransactionType {
    PURCHASE,
    CONSUMED,
    COOKED,
    DISCARDED,
    RESTOCK,
    USER_CORRECTION,
    ADJUSTMENT
}
```

---

## Core Domain Result & Error Model

Domain services return typed `DomainResult<T>` instead of raw Room/SQLite exceptions:

```kotlin
sealed interface DomainError {
    data class InsufficientQuantity(val available: Double, val requested: Double, val unit: QuantityUnit?) : DomainError
    data class ItemNotFound(val itemId: String) : DomainError
    data class InvalidQuantity(val quantity: Double) : DomainError
    data class InvalidUnitConversion(val from: QuantityUnit, val to: QuantityUnit) : DomainError
    data class ItemNotUsable(val itemId: String, val status: ItemStatus) : DomainError
    data class DuplicateOperation(val message: String) : DomainError
    data class TransactionFailed(val cause: String) : DomainError
}

sealed interface DomainResult<out T> {
    data class Success<out T>(val value: T) : DomainResult<T>
    data class Failure(val error: DomainError) : DomainResult<Nothing>
}
```

---

## Entities

### 1. `InventoryItem`
Represents an individual physical inventory lot owned or previously owned in household inventory.

| Field | Type | Modifiers / Notes |
|---|---|---|
| `id` | String | Primary Key (UUID) |
| `barcode` | String? | Index |
| `canonicalIngredientId` | String? | Index for canonical ingredient matching |
| `canonicalName` | String | Standardized ingredient name |
| `displayName` | String | User-visible display title |
| `brand` | String? | Brand name if applicable |
| `category` | FoodCategory | Enum |
| `storageLocation` | StorageLocation | Enum |
| `quantity` | Double? | Null if unknown |
| `quantityUnit` | QuantityUnit? | Enum |
| `packCount` | Int? | Number of packs/tins |
| `packSize` | Double? | Size per pack |
| `packSizeUnit` | QuantityUnit? | Enum |
| `quantityConfidence` | QuantityConfidence | EXACT, APPROXIMATE, UNKNOWN |
| `status` | ItemStatus | AVAILABLE, LOW, USE_SOON, OUT, DISCARDED, NEEDS_CONFIRMATION |
| `opened` | Boolean? | Null if unstated |
| `frozen` | Boolean | Default false |
| `staple` | Boolean | Default false |
| `purchaseDate` | String? | ISO-8601 Date (YYYY-MM-DD) |
| `openedDate` | String? | ISO-8601 Date |
| `useByDate` | String? | ISO-8601 Date |
| `bestBeforeDate` | String? | ISO-8601 Date |
| `notes` | String? | Freeform notes |
| `createdAt` | Long | Epoch millis |
| `updatedAt` | Long | Epoch millis |

---

### 2. `InventoryTransaction`
Audit log of all inventory movements and deductions.

| Field | Type | Modifiers / Notes |
|---|---|---|
| `id` | String | Primary Key (UUID) |
| `inventoryItemId` | String | Foreign Key -> `InventoryItem(id)` |
| `type` | TransactionType | PURCHASE, CONSUMED, COOKED, DISCARDED, RESTOCK, USER_CORRECTION, ADJUSTMENT |
| `quantityChange` | Double? | Delta (+ or -) |
| `unit` | QuantityUnit? | Unit of delta |
| `mealId` | String? | Linked meal ID if COOKED |
| `shoppingTripId` | String? | Linked shopping trip ID if PURCHASE |
| `note` | String? | Description |
| `timestamp` | Long | Epoch millis |

---

## Room Migration Policy & Schema Export

- Database version is explicitly set (`version = 1`).
- KSP Room schema export is configured to `$projectDir/schemas`.
- Destructive migration fallback is disallowed in release production builds.
