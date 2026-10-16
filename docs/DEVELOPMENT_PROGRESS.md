# Development Progress

## Current Phase & Milestone
- **Phase**: Physical Device Verification — Phase 6 and Phase 7 (Step 2: Complete User-Facing Runtime Acceptance)
- **Latest Commit**: `938009c097e7a179b0504b82dcaaa6fc8e98cce9` ("Verify Phase 6 and 7 on physical Android devices")

## Physical Device User-Facing Runtime Acceptance Matrix
1. **Shared Add/Edit Form**: PASS (OnePlus CPH2707 Main, API 36). All 21 fields accessible in `AddEditItemForm.kt` with Material 3 `ExposedDropdownMenuBox` selectors and collapsible progressive disclosure cards.
2. **Product Name Search**: PASS (OnePlus CPH2707 Main, API 36). `SearchProductDialog` in `AddEditItemForm.kt` queries local confirmed product database, prepopulates template fields, and preserves user-entered quantity (`quantityText`).
3. **Two-Pack Persistence**: PASS (OnePlus CPH2707 Main, API 36). `packCount = 2`, `packSize = 1.0`, `packSizeUnit = UNIT`, `quantity = 2.0`, `quantityUnit = PACK` (or `UNIT`) preserved without data loss or default resetting (`AddEditItemFormTest` & `InventoryDaoContractTest`).
4. **Product Name Parsing**: PASS (OnePlus CPH2707 Main, API 36). `BarcodeNormalizer.parseProductTitle` cleans quantity/pack prefixes ("10 free range eggs" -> "Free Range Eggs") while protecting brand names with leading numbers ("7UP Zero", "3M Command Hooks").
5. **Reduced-To-Clear / Discount Barcodes**: PASS (OnePlus CPH2707 Main, API 36). `BarcodeNormalizer.isVariableWeightOrInternalBarcode` flags store-internal prefixes (02, 04, 20-29), marking confidence `APPROXIMATE` without inferring false prices/dates.
6. **Phase 6 Genuine DocumentsUI Backup**: PASS (OnePlus CPH2707 Main, API 36). Settings screen "Save Encrypted Household Backup" action launches `ActivityResultContracts.CreateDocument("application/octet-stream")`, prompts for passphrase via `EncryptedBackupPassphraseDialog`, executes `MainEncryptedBackupSaveCoordinator`, writes through `DefaultSafDocumentStreamProvider`, performs ContentResolver readback, exact-byte check, authenticated decryption, and reports `SafBackupOutcome.VerifiedSuccess`.
7. **Follower Safety & Sync**: PASS (Samsung SM-G965F Follower, API 33). Launches cleanly in Follower role with 100% pairing, cursor, outbox, and bootstrap state preserved; Main-only backup action is hidden / disabled for Follower role.

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
