# Development Progress

## Current Phase & Milestone
- **Phase**: Phase 7 — Inventory Add & Barcode Scanning UX Improvements
- **Step 3**: Shared Add/Edit Item Form Component, Dropdown Selectors, and Progressive Disclosure
- **Latest Commit**: `89512e1758a769ddb815c5aa66b29e03ebff0cc0` ("Phase 7: Harden barcode mapping and quantity handling")

## Completed Milestones in Phase 6 & 7
1. **Phase 6 Step 1-11**: Complete SAF Encrypted Backup Foundation & Settings UI.
2. **Phase 7 Step 1**: Inventory Add & Barcode UX Assessment.
3. **Phase 7 Step 2**: Barcode Normalization and Quantity/Packaging Correctness (`BarcodeNormalizer`, `BarcodeLookupCoordinator`).
4. **Phase 7 Step 3**: Shared Add/Edit Item Form Component (`AddEditItemForm`), Dropdown Selectors (`ExposedDropdownMenuBox`), and Progressive Disclosure.

## Key Architectural Principles & Safety Constraints
- **Shared Form Component**: Created `AddEditItemForm.kt` exposing all 21 `InventoryItem` domain fields across manual and barcode Add/Edit entry flows.
- **Material 3 Dropdown Selectors**: Replaced unwieldy chip rows with clean `ExposedDropdownMenuBox` selectors for `QuantityUnit`, `FoodCategory`, and `StorageLocation`.
- **Progressive Disclosure**: Primary essential fields stay visible; optional fields are grouped into collapsible cards ("Packaging & Brand Details", "Dates & Attributes", "Additional Notes") preserving entered state across toggles.
- **Two-Pack Scenario Verification**: Unit test `toInventoryItem_preservesPackageCountAndSize_twoPackScenario` verifies `packCount = 2`, `packSize = 1.0`, `packSizeUnit = QuantityUnit.UNIT`, `quantity = 2.0` mapping without data loss or defaulting.
- **Zero Room Schema Changes**: All 21 fields already exist in AppDatabase Room v16 and FollowerDatabase v4.
- **Sync Safety**: All updates flow through `InventoryDomainServiceImpl` preserving canonical operations for Follower sync.
- **Physical Device Protection**: OnePlus Main (CPH2707) and Samsung Follower (SM-G965F) remain 100% isolated and untouched.

## Test Evidence
- **JVM Unit Tests**: 782 passed, 0 failed, 0 skipped.
- **Android Instrumentation Tests**: 100% compiled (`:app:compileDebugAndroidTestSources` PASSED).
- **Lint**: 0 errors (`:app:lintDebug` PASSED).

## Deferred — Inventory Add & Barcode Scanning Improvements
1. **Barcode Scanner Field Consistency**: Make barcode-scanner Add Item fields/options available consistently across manual and other Add/Edit flows.
2. **Packaging & Quantity Mapping**: Correct inventory quantity and packaging mapping so entering two pizza packs does not save as one unit.
3. **Product Name Parsing**: Improve barcode product-name parsing so "10 free range eggs" does not conflate product name, pack size, package count, and inventory quantity.
4. **Reduced-To-Clear / Discount Barcodes**: Support retailer reduced-to-clear/discount sticker barcodes where feasible, with safe fallback to original barcode, name search, or manual entry; avoid inferring unavailable price or expiry information.
5. **Food Database Lookup**: Add food database lookup by product name, including product selection and prepopulation of Add Item fields.
6. **Form UX & Progressive Disclosure**: Replace long single-choice radio-button lists with appropriate dropdowns; consider a dedicated reusable Add/Edit Item screen with progressive disclosure, validation, and preservation of entered values.

## Next Milestone
- Proceed to **Phase 7 Step 4** (Integration into Scanner Flow & Final Phase 7 Audit).
