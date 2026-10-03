# Release Checklist & Hardening Verification

This document specifies the pre-release blockers, security assertions, and build checks for Pantry Planner.

---

## 1. Hard Release Blockers (All PASSED)

- [x] **Migration Chain Integrity**: Non-destructive migrations `1_2`, `2_3`, `3_4`, `4_5` verified via `MigrationChainTest`.
- [x] **Release Security**: Cleartext HTTP disabled in release builds (`usesCleartextTraffic = false`).
- [x] **Fixture Isolation**: Production `RetailRepositoryImpl` asserts `sourceType != RetailOfferSourceType.FIXTURE`.
- [x] **Production Authentication**: `BackendAuthenticator` enforces production session token/JWT validation; dev token disabled in production mode.
- [x] **Data Safety**: Atomic import with compensating rollback verified via `BackupImportExportTest`.
- [x] **Write Authorization**: AI tool execution respects `ActionAuthorizationContext` and `AuthorizedMutationScope`.
- [x] **Cooking Confirmation Safety**: Cook confirmation requires explicit UI approval before inventory deduction.
- [x] **No Hardcoded Secrets**: Zero API keys or secrets in Android source code or `.apk`.
