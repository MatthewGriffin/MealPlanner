# Development Progress

## Current Phase & Milestone
- **Phase**: Phase 6 — Storage Access Framework (SAF) Encrypted Household Backup Foundation & User UI
- **Step 8**: End-to-End Backup Verification, Security Hardening, and Final Automated Audit
- **Latest Commit**: `b3e0c9d36be008cfe7320d31a1022dd4b2b73e94` ("Phase 6: Add Main-only encrypted backup Settings UI")

## Completed Milestones in Phase 6
1. **Phase 6 Step 1**: Save Main inventory exports via SAF (`PantryPlannerExportDto`).
2. **Phase 6 Step 2**: Internal Main household backup snapshot foundation (`MainHouseholdBackupDto`).
3. **Phase 6 Step 3**: Versioned Main backup container (`PANTRYBK`), integrity validation, and negative parser tests.
4. **Phase 6 Step 4**: Portable encrypted Main backup container (`PANTRYENC`, AES-256-GCM + PBKDF2WithHmacSHA256, 250k iterations).
5. **Phase 6 Step 5**: Encryption hardening & Android native JCA crypto compatibility tests.
6. **Phase 6 Step 6A-6B**: Internal Main-only encrypted SAF backup writer & verified readback (`MainEncryptedBackupSafWriter` & `MainEncryptedBackupSaveCoordinator`).
7. **Phase 6 Step 6C-6D**: Android SAF provider stream integration test harness (`PantryPhase6Step6cAndroidSafIntegrationTest` on AVD API 36 `emulator-5554`).
8. **Phase 6 Step 7A-7B**: User-facing Main-only encrypted backup UI integration in `SettingsScreen` and `SettingsViewModel`.
9. **Phase 6 Step 8**: End-to-End Backup Verification, Security Hardening, and Final Automated Audit.

## Key Architectural Principles & Safety Constraints
- **Main Role Guard**: Backup creation is available on Main device only (`AppRoleResolver.isMain()`).
- **Passphrase Security**: Passphrases are never persisted in SharedPreferences, DataStore, or UiState. Temporary CharArrays are wiped in memory (`Arrays.fill(passphrase, ' ')`).
- **SAF Stream I/O**: Opens streams via `ContentResolver`, flushes/closes output, re-opens input, performs exact-byte `contentEquals` readback verification, and validates authenticated decryption.
- **Typed Outcomes**: `SafBackupOutcome.VerifiedSuccess`, `SafBackupOutcome.NoDocumentIoAttempted`, `SafBackupOutcome.DocumentIoAttemptedUnverified`.
- **Restore Disabled**: Restore functionality remains 100% disabled and unexposed.
- **Physical Device Protection**: OnePlus Main (CPH2707) and Samsung Follower (SM-G965F) were 100% isolated and untouched.

## Test Evidence
- **JVM Unit Tests**: 774 passed, 0 failed, 0 skipped.
- **Android Instrumentation Tests**: 100% compiled & passing on AVD API 36 (`emulator-5554`).
- **Lint**: 0 errors (`:app:lintDebug` PASSED).

## Deferred — Inventory Add & Barcode Scanning Improvements
1. **Barcode Scanner Field Consistency**: Make barcode-scanner Add Item fields/options available consistently across manual and other Add/Edit flows.
2. **Packaging & Quantity Mapping**: Correct inventory quantity and packaging mapping so entering two pizza packs does not save as one unit.
3. **Product Name Parsing**: Improve barcode product-name parsing so "10 free range eggs" does not conflate product name, pack size, package count, and inventory quantity.
4. **Reduced-To-Clear / Discount Barcodes**: Support retailer reduced-to-clear/discount sticker barcodes where feasible, with safe fallback to original barcode, name search, or manual entry; avoid inferring unavailable price or expiry information.
5. **Food Database Lookup**: Add food database lookup by product name, including product selection and prepopulation of Add Item fields.
6. **Form UX & Progressive Disclosure**: Replace long single-choice radio-button lists with appropriate dropdowns; consider a dedicated reusable Add/Edit Item screen with progressive disclosure, validation, and preservation of entered values.

## Next Milestone
- Proceed to **Phase 7** (or next user-authorized phase).
