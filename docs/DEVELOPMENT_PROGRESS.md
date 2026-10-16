# Development Progress

## Current Phase & Milestone
- **Phase**: Phase 7 — Inventory Add & Barcode Scanning UX Improvements
- **Step 2**: Barcode Normalization and Quantity/Packaging Correctness
- **Latest Commit**: `18c287ccef11552a8eec700cf1304f6170ef3da5` ("Phase 6: Record Android runtime verification results")

## Completed Milestones in Phase 6 & 7
1. **Phase 6 Step 1-11**: Complete SAF Encrypted Backup Foundation & Settings UI.
2. **Phase 7 Step 1**: Inventory Add & Barcode UX Assessment.
3. **Phase 7 Step 2**: Barcode Normalization and Quantity/Packaging Correctness (`BarcodeNormalizer`, `BarcodeLookupCoordinator`).

## Key Architectural Principles & Safety Constraints
- **Barcode Name Normalization**: `BarcodeNormalizer.parseProductTitle` extracts leading quantity/pack size prefixes (e.g. "10 free range eggs" -> "Free Range Eggs" + pack size 10) without corrupting brand names with numbers (e.g. "7UP Zero", "3M Command Hooks").
- **Variable-Weight / Store-Internal Barcodes**: `BarcodeNormalizer.isVariableWeightOrInternalBarcode` detects store-internal barcode prefixes (02, 04, 20-29), marking quantity confidence as `APPROXIMATE` so users verify weights without inferring false prices/dates.
- **Packaging Disambiguation**: Package count (`packCount`), pack size (`packSize` & `packSizeUnit`), and total quantity (`quantity`) remain distinct and mapped without overwriting user inputs.
- **Zero Room Schema Changes**: All 21 fields already exist in AppDatabase Room v16 and FollowerDatabase v4.
- **Sync Safety**: All updates flow through `InventoryDomainServiceImpl` preserving canonical operations for Follower sync.
- **Physical Device Protection**: OnePlus Main (CPH2707) and Samsung Follower (SM-G965F) remain 100% isolated and untouched.

## Test Evidence
- **JVM Unit Tests**: 779 passed, 0 failed, 0 skipped.
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
- Proceed to **Phase 7 Step 3** (Shared Add/Edit Item Form Component `AddEditItemForm` with Dropdown Selectors & Progressive Disclosure).
