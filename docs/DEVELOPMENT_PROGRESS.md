# Development Progress

## Current Phase & Milestone
- **Phase**: Phase 7 — Inventory Add & Barcode Scanning UX Improvements
- **Step 1**: Inventory Add & Barcode UX Assessment
- **Latest Commit**: `8d848524353f734a2fffc1c890e0e9131c32ab29` ("Phase 6: Verify and harden encrypted backup flow")

## Completed Milestones in Phase 6
1. **Phase 6 Step 1**: Save Main inventory exports via SAF (`PantryPlannerExportDto`).
2. **Phase 6 Step 2**: Internal Main household backup snapshot foundation (`MainHouseholdBackupDto`).
3. **Phase 6 Step 3**: Versioned Main backup container (`PANTRYBK`), integrity validation, and negative parser tests.
4. **Phase 6 Step 4**: Portable encrypted Main backup container (`PANTRYENC`, AES-256-GCM + PBKDF2WithHmacSHA256, 250k iterations).
5. **Phase 6 Step 5**: Encryption hardening & Android native JCA crypto compatibility tests.
6. **Phase 6 Step 6A-6B**: Internal Main-only encrypted SAF backup writer & verified readback (`MainEncryptedBackupSafWriter` & `MainEncryptedBackupSaveCoordinator`).
7. **Phase 6 Step 6C-6D**: Android SAF provider stream integration test harness (`PantryPhase6Step6cAndroidSafIntegrationTest`).
8. **Phase 6 Step 7A-7B**: User-facing Main-only encrypted backup UI integration in `SettingsScreen` and `SettingsViewModel`.
9. **Phase 6 Step 8**: End-to-End Backup Verification, Security Hardening, and Final Automated Audit.
10. **Phase 6 Step 9**: Close Encrypted Backup Verification Gaps (in-memory Room database snapshot & SAF stream readback test).
11. **Phase 6 Step 10**: Execute Existing Android Instrumentation Tests and Resolve Final Gaps.
12. **Phase 6 Step 11**: Runtime Verification and Blocker Audit.

## Phase 7 Assessment Findings
1. **Field Consistency**: `AddEditItemDialog` in `InventoryScreen.kt` currently only exposes Name, Quantity, and Unit. Missing fields (`brand`, `barcode`, `category`, `storageLocation`, `useByDate`, `notes`, `staple`, `packCount`, `packSize`) will be integrated into a unified form component.
2. **Packaging & Quantity Mapping**: Disambiguate package count (`packCount`), pack size (`packSize` & `packSizeUnit`), and total inventory quantity (`quantity = packCount * packSize`).
3. **Product Name Parsing**: Improve `BarcodeNormalizer` / `BarcodeLookupCoordinator` to parse prefix counts/weights (e.g. "10 free range eggs") into `packCount` or `packSize`, preventing name/quantity conflation.
4. **Reduced-To-Clear Barcodes**: Handle variable-weight / store-internal barcode prefixes (02, 20-29, 04) safely with fallback to name lookup or manual entry without inferring false prices/dates.
5. **Food Database Lookup**: Add name-based product search in `BarcodeMappingDao` / `BarcodeLookupCoordinator` to prepopulate Add Item fields when typing or searching.
6. **Form UX & Progressive Disclosure**: Replace long chip rows / radio button lists with clean `ExposedDropdownMenuBox` dropdown selectors (`FoodCategory`, `StorageLocation`, `QuantityUnit`) and collapsible optional form sections.

## Proposed Phase 7 Implementation Steps
- **Phase 7 Step 2**: Core Domain & Barcode Normalizer Hardening (package count/size parsing, name cleaning, discount barcode prefix handling).
- **Phase 7 Step 3**: Shared Add/Edit Item Form Component (`AddEditItemForm`) with Dropdown Selectors & Progressive Disclosure.
- **Phase 7 Step 4**: Integration into `InventoryScreen`, `InventoryViewModel`, and Scanner Flow with Unit Tests.

## Safety Constraints
- **Zero Room Schema Changes**: All 21 fields already exist in AppDatabase Room v16.
- **Sync Safety**: All updates flow through `InventoryDomainServiceImpl` preserving canonical operations for Follower sync.
- **Physical Device Protection**: OnePlus Main (CPH2707) and Samsung Follower (SM-G965F) remain 100% isolated and untouched.

## Test Evidence
- **JVM Unit Tests**: 774 passed, 0 failed, 0 skipped.
- **Android Instrumentation Tests**: 100% compiled (`:app:compileDebugAndroidTestSources` PASSED).
- **Lint**: 0 errors (`:app:lintDebug` PASSED).

## Next Milestone
- Proceed to **Phase 7 Step 2** (Core Domain & Barcode Normalizer Hardening).
