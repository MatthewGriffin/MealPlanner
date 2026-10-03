# AI Tool Contracts & Function Calling Specification

This document defines the structured tools exposed to the AI reasoning engine and the safety execution flow.

---

## 1. Safety Execution Pipeline
The AI engine CANNOT directly execute SQL statements or modify Room tables directly.
All tool calls route through `AiToolDispatcher`:

```
AI Model Tool Call Request
       │
       ▼
AiToolDispatcher (Schema validation & parameter sanitization)
       │
       ▼
Domain Service Layer (Enforces business rules & invariants)
       │
       ▼
Repository Layer (Room Transactions & DAO execution)
```

---

## 2. Tool Contract Definitions

### Inventory Query Tools
1. `get_current_inventory(category: String?, storageLocation: String?, status: String?)`
   - Returns list of matching `InventoryItem` objects.
2. `find_inventory_item(query: String)`
   - Fuzzy search on canonical or display names.
3. `get_low_stock_items()`
   - Returns items with status `LOW` or below staple thresholds.
4. `get_use_soon_items()`
   - Returns items marked `USE_SOON` or nearing expiry.

### Meal & History Query Tools
5. `get_recent_meals(daysLookback: Int = 14)`
   - Returns meal history to verify repetition penalties.
6. `get_leftovers()`
   - Returns currently available `Leftover` records.
7. `get_meal_plan(startDate: String, endDate: String)`
   - Returns planned meals for date range.

### Meal & State Mutation Tools
8. `create_meal(date: String, mealType: String, title: String, servings: Int, recipeJson: String)`
   - Creates planned meal record (does NOT deduct inventory).
9. `update_meal(mealId: String, title: String?, recipeJson: String?)`
   - Modifies planned meal details.
10. `move_meal(mealId: String, newDate: String)`
    - Moves planned meal to a different date.
11. `mark_meal_cooked(mealId: String)`
    - Triggers single atomic Room transaction: sets meal status `COOKED`, deducts ingredients, creates transaction audit logs, creates leftovers if applicable.
12. `mark_meal_skipped(mealId: String)`
    - Sets meal status to `SKIPPED`.

### Inventory Mutation Tools
13. `mark_inventory_item_out(itemId: String)`
    - Sets item status to `OUT` and quantity to 0.
14. `adjust_inventory_quantity(itemId: String, newQuantity: Double?, unit: String?, confidence: String)`
    - Adjusts inventory quantity directly.
15. `mark_item_discarded(itemId: String, reason: String?)`
    - Sets item status to `DISCARDED` and logs transaction.

### User & Preferences Tools
16. `get_user_preferences()`
    - Returns current household size and planning rules configuration.

### Shopping & Price Tools
17. `calculate_missing_ingredients(mealId: String)`
    - Calculates required recipe ingredients minus usable owned inventory.
18. `search_products(query: String, retailer: String?)`
    - Searches retailer database for candidate purchases.
19. `compare_store_baskets(missingItems: List<String>)`
    - Runs multi-store optimizer across Asda and Food Warehouse.
20. `create_shopping_list(items: List<ShoppingItemSpec>)`
    - Creates or updates shopping list with store assignments.
