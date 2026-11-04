# Release Checklist & Hardening Verification

This document specifies the pre-release blockers, security assertions, and build checks for Pantry Planner.

---

## 1. Hard Release Blockers

- [x] **Migration Chain Integrity (early versions)**: Non-destructive migrations `1_2`, `2_3`, `3_4`, `4_5` verified via `MigrationChainTest`.
- [ ] **Migration Chain Integrity (to v21)**: Later migrations have no end-to-end check against the exported Room schemas. `Migration20To21Test` builds its v20 schema by hand.
- [x] **Release Security**: Cleartext HTTP disabled in release builds (`usesCleartextTraffic = false`).
- [x] **Production Authentication**: `BackendAuthenticator` enforces production session token/JWT validation; dev token disabled in production mode.
- [x] **Data Safety**: Atomic import with compensating rollback verified via `BackupImportExportTest`.
- [x] **Write Authorization**: AI tool execution respects `ActionAuthorizationContext` and `AuthorizedMutationScope`.
- [x] **Cooking Confirmation Safety**: Cook confirmation requires explicit UI approval before inventory deduction.
- [x] **No Hardcoded Secrets (app)**: No API keys or secrets in Android source code or `.apk`.
- [ ] **No Hardcoded Secrets (backend)**: `:backend` falls back to a built-in dev token when `DEV_AUTH_TOKEN` is unset.
