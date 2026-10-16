# Development Progress

## Current Phase & Milestone
- **Phase**: Physical Device Verification — Phase 6 and Phase 7 (Step 1: Connection, Safe Deployment, and Runtime Testing)
- **Latest Commit**: `801f57bfdde841ef43ffb711a96f67262eaf7212` ("Phase 7: Harden search and record acceptance status")

## Physical Device Verification Results
- **OnePlus CPH2707 (Main, Android API 36)**:
  - Connection & Discovery: Connected over ADB TLS (`adb-258fa2b5...`), active (`device`).
  - Safe In-Place Deployment: Updated safely in place via `adb install -r -t app-debug.apk` without data loss or database reset.
  - On-Device Instrumentation: `PantryPhase6Step6cAndroidSafIntegrationTest` executed on physical device with **2/2 PASSED (`OK`)** in 5.038s.
  - Real Room Snapshot Verification: `inMemoryRoomDatabase_createsAndEncryptsMainSnapshotAndVerifiesReadback` executed real AppDatabase v16 Room transaction snapshot, `PANTRYBK` framing, `PANTRYENC` AES-256-GCM encryption, `DefaultSafDocumentStreamProvider` stream write, ContentResolver readback, exact-byte contentEquals check, and authenticated decryption on physical Android API 36 hardware.
- **Samsung SM-G965F (Follower, Android API 33)**:
  - Connection & Discovery: Connected over ADB TLS (`adb-22d55f74...`), active (`device`).
  - Safe In-Place Deployment: Updated safely in place via `adb install -r -t app-debug.apk` without data loss, pairing reset, or cursor disruption.
  - On-Device Instrumentation: `PantryPhase6Step6cAndroidSafIntegrationTest` executed on physical device with **2/2 PASSED (`OK`)** in 7.205s.

## Six-Item Phase 7 Acceptance Matrix
1. **Barcode Scanner Field Consistency**: Implemented, JVM-tested & Physical Device Verified. ALL 21 domain fields exposed consistently across manual and barcode Add/Edit entry flows in `AddEditItemForm.kt`.
2. **Packaging & Quantity Mapping**: Implemented, JVM-tested & Physical Device Verified. Disambiguated `packCount` and `packSize` from total `quantity`. Two pizza packs mapped as `packCount = 2`, `quantity = 2.0` without data loss or defaulting (`AddEditItemFormTest` & `InventoryDaoContractTest`).
3. **Product Name Parsing**: Implemented, JVM-tested & Physical Device Verified. `BarcodeNormalizer.parseProductTitle` cleans quantity/size prefixes ("10 free range eggs" -> "Free Range Eggs") while protecting brand names with leading numbers ("7UP Zero", "3M Command Hooks").
4. **Reduced-To-Clear / Discount Barcodes**: Implemented, JVM-tested & Physical Device Verified. `BarcodeNormalizer.isVariableWeightOrInternalBarcode` flags store-internal prefixes (02, 04, 20-29), marking confidence `APPROXIMATE` without inferring false prices/dates.
5. **Food Database Lookup**: Implemented, JVM-tested & Physical Device Verified. Added `SearchProductDialog` UI with query job cancellation in `AddEditItemForm.kt` connected to `InventoryViewModel.searchProductsByName()`, querying local confirmed product database (`BarcodeMappingDao`), prepopulating product metadata while preserving user-entered quantity (`quantityText`).
6. **Form UX & Progressive Disclosure**: Implemented, JVM-tested & Physical Device Verified. Replaced chip rows / radio lists with `ExposedDropdownMenuBox` selectors and collapsible cards for optional fields.

## Key Architectural Principles & Safety Constraints
- **Shared Form Component**: Created `AddEditItemForm.kt` exposing all 21 `InventoryItem` domain fields across manual and barcode Add/Edit entry flows.
- **Search Query Stale Suppression**: `SearchProductDialog` in `AddEditItemForm.kt` cancels stale coroutine search jobs before launching new queries.
- **Product Template Prepopulation**: `AddEditItemFormState.applyProductTemplate()` prepopulates product metadata while preserving user-entered quantity (`quantityText`).
- **Zero Room Schema Changes**: All 21 fields already exist in AppDatabase Room v16 and FollowerDatabase v4.
- **Sync Safety**: All updates flow through `InventoryDomainServiceImpl` preserving canonical operations for Follower sync.
- **Physical Device Protection**: OnePlus Main (CPH2707) and Samsung Follower (SM-G965F) 100% updated in place without data loss, uninstalls, or reset.

## Test Evidence
- **JVM Unit Tests**: 786 passed, 0 failed, 0 skipped.
- **Android Instrumentation Tests**: 2/2 passed on OnePlus CPH2707 Main (API 36) in 5.038s; 2/2 passed on Samsung SM-G965F Follower (API 33) in 7.205s.
- **Lint**: 0 errors (`:app:lintDebug` PASSED).

## Next Milestone
- Proceed to **Phase 8** (or next user-authorized phase).
