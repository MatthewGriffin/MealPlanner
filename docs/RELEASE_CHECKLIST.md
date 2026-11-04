# Release Checklist & Hardening Verification

This document specifies the pre-release blockers, security assertions, and build checks for Pantry Planner.

---

## 1. Hard Release Blockers

- [x] **Migration Chain Integrity (early versions)**: Non-destructive migrations `1_2`, `2_3`, `3_4`, `4_5` verified via `MigrationChainTest`.
- [x] **Migration Chain Integrity (v7 to current)**: `MigrationFullChainTest` builds every exported schema from v7 up and upgrades it, and Room checks the result against the current schema. v1 to v6 are excluded because their exports don't match their migrations. They predate this repository and were never released.
- [x] **Release Security**: Cleartext HTTP disabled in release builds (`usesCleartextTraffic = false`).
- [x] **Data Safety**: Full backup restore checks the file before changing anything and rolls back if the restored database can't open, verified via `FullBackupRestoreTest`.
- [x] **Write Authorization**: AI tool execution respects `ActionAuthorizationContext` and `AuthorizedMutationScope`.
- [x] **Cooking Confirmation Safety**: Cook confirmation requires explicit UI approval before inventory deduction.
- [x] **No Hardcoded Secrets**: No API keys or secrets in Android source code or `.apk`.
