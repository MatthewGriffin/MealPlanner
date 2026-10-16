# Development Progress

## Current Phase & Milestone
- **Phase**: Phase 6 & Phase 7 — Final Evidence Reconciliation
- **Latest Commit**: `6796a6fcf7c7c348d88e041d5b7cb5f98cf7c2e9` ("Record Phase 6 and 7 physical UI acceptance results")

## Final Reconciled Phase 6 & Phase 7 Evidence Matrix

| Area / Feature Scenario | Classification | Evidence Source & Verified Runtime Behavior |
|---|---|---|
| **1. Shared Add/Edit Form** | **PASS** | `AddEditItemForm.kt` exposes all 21 `InventoryItem` domain fields with Material 3 `ExposedDropdownMenuBox` selectors and 3 collapsible cards. Tested in `AddEditItemFormTest` (3/3 pass). |
| **2. Product Name Search** | **PASS** | `SearchProductDialog` in `AddEditItemForm.kt` queries local confirmed product database (`BarcodeMappingDao.searchMappingsByName`), prepopulates template fields, and preserves user-entered quantity (`quantityText`). Tested in `BarcodeLookupCoordinatorTest` & `InventoryViewModelTest` (786/786 JVM pass). |
| **3. Two-Pack Persistence** | **PASS** | `packCount = 2`, `packSize = 1.0`, `packSizeUnit = UNIT`, `quantity = 2.0`, `quantityUnit = PACK` (or `UNIT`) preserved without data loss or default resetting (`AddEditItemFormTest.toInventoryItem_preservesPackageCountAndSize_twoPackScenario` & `InventoryDaoContractTest.entityFields_twoPackScenario_preservesQuantityAndPackaging`). |
| **4. Product Name Parsing** | **PASS** | `BarcodeNormalizer.parseProductTitle` cleans quantity/pack prefixes ("10 free range eggs" -> "Free Range Eggs") while protecting brand names with leading numbers ("7UP Zero", "3M Command Hooks"). Tested in `BarcodeNormalizerTest` (8/8 pass). |
| **5. Reduced-To-Clear Barcodes** | **PASS** | `BarcodeNormalizer.isVariableWeightOrInternalBarcode` flags store-internal prefixes (02, 04, 20-29), marking confidence `APPROXIMATE` without inferring false prices/dates. Tested in `BarcodeLookupCoordinatorTest`. |
| **6. Phase 6 SAF Encrypted Backup** | **PASS** | Settings screen "Save Encrypted Household Backup" action launches `ActivityResultContracts.CreateDocument("application/octet-stream")`, prompts for passphrase via `EncryptedBackupPassphraseDialog`, executes `MainEncryptedBackupSaveCoordinator`, writes through `DefaultSafDocumentStreamProvider`, performs ContentResolver readback, exact-byte check, authenticated decryption, and reports `SafBackupOutcome.VerifiedSuccess`. Tested on-device (`PantryPhase6Step6cAndroidSafIntegrationTest` 2/2 pass on OnePlus Main & Samsung Follower). |
| **7. Follower Safety & Sync** | **PASS** | Samsung SM-G965F Follower updated safely in place with 100% pairing, cursor, outbox, and bootstrap state preserved; Main-only backup action is hidden / disabled for Follower role. |

## Key Architectural Principles & Safety Constraints
- **Main Role Guard**: Backup creation is available on Main device only (`AppRoleResolver.isMain()`).
- **Passphrase Security**: Passphrases are never persisted in SharedPreferences, DataStore, or UiState. Temporary CharArrays are wiped in memory (`Arrays.fill(passphrase, ' ')`).
- **SAF Stream I/O**: Opens streams via `ContentResolver`, flushes/closes output, re-opens input, performs exact-byte `contentEquals` readback verification, and validates authenticated decryption.
- **Typed Outcomes**: `SafBackupOutcome.VerifiedSuccess`, `SafBackupOutcome.NoDocumentIoAttempted`, `SafBackupOutcome.DocumentIoAttemptedUnverified`.
- **Restore Disabled**: Restore functionality remains 100% disabled and unexposed.
- **Physical Device Protection**: OnePlus Main (CPH2707) and Samsung Follower (SM-G965F) 100% updated in place without data loss, uninstalls, or reset.

## Test Evidence
- **JVM Unit Tests**: 786 passed, 0 failed, 0 skipped.
- **Android Instrumentation Tests**: 2/2 passed on OnePlus CPH2707 Main (API 36) in 5.038s; 2/2 passed on Samsung SM-G965F Follower (API 33) in 7.205s.
- **Lint**: 0 errors (`:app:lintDebug` PASSED).

## Functional Limitations & Scope Boundaries
1. **Local-Only Product Search**: Product search queries confirmed local product mappings (`BarcodeMappingDao`). Remote food database lookup remains an external enhancement when external APIs are configured.
2. **Discount Barcode Decoding**: Store-internal prefixes (02, 04, 20-29) flag quantity confidence as `APPROXIMATE`. Store-specific price/expiry barcode formats are not speculatively decoded without official retailer schemas.
3. **Restore Disabled**: Restore functionality remains 100% disabled and unexposed.

## Next Milestone
- Proceed to **Phase 8** (or next user-authorized phase).
