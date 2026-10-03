# Build Phases & Implementation Roadmap

This document defines the incremental build phases for Pantry Planner.

---

## Phase Breakdown

### Phase 1: Foundation & Core Inventory (Completed & Hardened)
- Package/module architecture establishment.
- Room Database setup (Schema, Entities, DAOs, TypeConverters).
- KSP Room schema export ($projectDir/schemas).
- Hilt Dependency Injection setup (`DatabaseModule`, `RepositoryModule`, `DomainModule`).
- Domain Error Model (`DomainResult`, `DomainError`) and Deterministic Unit Converter (`UnitConverter`).
- Physical Inventory Lot tracking model with explicit usable inventory query contracts.
- Atomic Room transaction runner (`DatabaseTransactionRunner`).
- Core Repositories & Domain Services (`InventoryRepository`, `TransactionRepository`, `InventoryDomainService`).
- 33 Unit Tests for core inventory invariants, quantity safety, idempotency, user corrections, and conversions.
- Inventory Compose UI (Tabs: ALL, CUPBOARD, FRIDGE, FREEZER, USE SOON, LOW, OUT; Add/Edit Item, Correction, Mark Out/Discarded).

### Phase 2: Barcode Scanning & Product Lookup (Completed & Hardened)
- CameraX & ML Kit Barcode Scanning integration (`BarcodeAnalyzer`, `CameraPermissionHandler`).
- `ProductLookupRepository` with Open Food Facts API adapter (`OpenFoodFactsProductDataProvider`).
- Local barcode lookup cache in Room (`ProductBarcodeMappingEntity` & `BarcodeMappingDao`).
- Normalization, quantity parsing, and manual product entry fallback flow.
- Unit & integration tests (57 passing unit tests total across Phase 1 & Phase 2).

### Phase 3: Meals, Leftovers & Inventory-Deducting Cooking Engine (Completed & Hardened)
- `Meal`, `MealIngredient`, `Leftover`, `ActualIngredientOverride`, `IngredientSubstitution` models and Room Entities (`MealEntity`, `MealIngredientEntity`, `LeftoverEntity`).
- Database migration `MIGRATION_1_2` updating Room version from 1 to 2.
- `MealDao` and `LeftoverDao` with disambiguated queries (`getRecentCookedMeals(daysLookback)` vs `getUpcomingPlannedMeals(startDate, endDate)`).
- `MealRepository` and `LeftoverRepository` implementations.
- `MealDomainServiceImpl` with `runInDomainTransaction` atomic rollback guarantee (`TransactionAbortException` triggering 100% SQLite ROLLBACK).
- Multipack splitting logic for Case A (exact inner unit), Case B (partial inner unit creating residual opened lot), and Case C (full unit consumption + inner unit split).
- Portion invariants (`batchPortions == immediatePortions + leftoverPortions`, non-negative portions).
- Automatic leftover creation and consumption tracking (`LeftoverStatus.AVAILABLE` -> `CONSUMED` / `DISCARDED`).
- 70 unit tests passing cleanly across Phase 1, Phase 2, and Phase 3.
- Phase 3 Compose UI: `MealPlannerViewModel`, `MealPlannerScreen`, `CookConfirmationDialog`, `AddEditMealDialog`, `LeftoversScreen`, `MealHistoryScreen`, and top-level `MainActivity` bottom navigation bar.

### Phase 4: Deterministic Shopping List Generation Engine (Completed & Hardened)
- `ShoppingList`, `ShoppingListItem`, `ShoppingRequirementSource`, `StapleRule` models and Room Entities (`ShoppingListEntity`, `ShoppingListItemEntity`, `ShoppingRequirementSourceEntity`, `StapleRuleEntity`).
- Database migration `MIGRATION_2_3` updating Room version from 2 to 3.
- `ShoppingDao` and `StapleRuleDao`.
- `ShoppingRepository` and `StapleRuleRepository` implementations.
- `ShoppingDomainServiceImpl` with `runInDomainTransaction` atomic list regeneration guarantee.
- Invariant enforcement: `finalSuggestedPurchaseQuantity = mealShortfallQuantity + stapleReserveTopUpQuantity`.
- Final expected stock formula: `expectedFinalInventory = max(0, knownUsableQuantity + finalSuggestedPurchaseQuantity - plannedRequirement)`.
- Uncertainty safety: Known stock is preserved while marking `quantityUncertain = true`, `requirementConfidence = NEEDS_CONFIRMATION` without false staple top-ups.
- Separate `QUANTITY` vs `PACK_COUNT` staple restock modes.
- Preservation of `MANUAL` items and valid `CHECKED` states (resetting `CHECKED` if requirement increases).
- Lot-targeted `resolveInventoryLotUncertainty`.
- 80 unit tests passing cleanly across Phase 1, Phase 2, Phase 3, and Phase 4.
- Phase 4 Compose UI: `ShoppingViewModel`, `ShoppingScreen`, `AddManualItemDialog`, `StapleRulesScreen`, `ShoppingItemDetailDialog`, and top-level `MainActivity` bottom navigation bar.

### Phase 5: AI Abstraction & Local Validation Engine
- `MealPlannerAiProvider` interface and mock AI provider.
- `AiToolDispatcher` tool calling infrastructure.
- `AiContextBuilder` for prompt context generation.
- `RecipeValidator` for validating AI responses against database state.

### Phase 6: Runtime AI Backend Integration
- Secure HTTPS backend proxy integration.
- OpenAI and Gemini provider adapters.
- Provider settings UI (model selection, reasoning level).
- System prompt versioning and master prompt management.

### Phase 7: Retail Price Optimization Integration
- `RetailerProvider` interface abstractions.
- Asda and Iceland / Food Warehouse provider adapters.
- Price quote caching with TTL.
- Live store basket comparison in Shopping UI.

### Phase 8: Polish, Expiry Alerts & Analytics
- Background WorkManager task for expiry notifications (`USE TODAY`, `USE WITHIN 2 DAYS`).
- Recipe presentation cards in Compose Chat UI.
- Meal rating, history, and food waste reduction analytics.
