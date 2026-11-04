# Backup and Restore

## Encrypted household backup (`pantry-full-backup-v2`)

This is the supported way to move data to another phone, using **Save Encrypted Household Backup** and **Restore from Backup** in Settings on the Main phone. Android cloud backup and device transfer are off, because Keystore keys don't transfer.

### What's included
- The whole main Room database, taken with `VACUUM INTO`, so new tables are included automatically.
- Non-secret shared preferences.
- The Gemini API key and the sync TLS identity. Anyone with the file and passphrase can read these.

Follower phones aren't backed up, because they resync from Main.

### File format
A `PANTRYENC` header with wire version 2, then a zip archive encrypted with AES-256-GCM using a PBKDF2-derived key. Wire version 1 files have an older format with only some of the data, and can't be restored.

### Restore
1. **Check:** decrypt, read the archive, reject backups from a newer schema, and run `PRAGMA integrity_check`. Nothing on the phone changes if any check fails.
2. **Revoke:** mark paired devices as revoked in the staged copy, because a restore rolls Main back in time.
3. **Install:** on the next app start, before dependency injection, swap the databases and open the result through Room so migrations run.
4. **Roll back:** if the restored database can't open, or the install was interrupted, put the previous database back.
5. **Finish:** apply credentials and preferences, then delete the staged files. A credential failure keeps the restored data and shows a warning.

### Compatibility
Every backup from wire version 2 onward must stay restorable by all later versions of the app, because updating is one of the main reasons to make one. Older databases inside a backup are brought up to date by the normal migrations, so each schema change needs a working migration. If the file format has to change, keep reading the old format as well.

`FullBackupCompatibilityTest` enforces this with a real backup file, `app/src/test/resources/backup/full-backup-wire2-schema22.pantryenc`. It only holds made-up data. Never regenerate it: if that test fails, the change being tested would strand existing backups.

### Joined phones after a restore
Revoked phones show as needing re-authorization in Household settings. Re-pairing clears their bootstrap marker, so they replace local inventory with a fresh snapshot and restart their feed from it. A phone that joined after the backup was made has no device record, so it has to join again.

---

# Inventory export (`pantry-planner-export-v1`)

The plain JSON export in Settings contains inventory items only, for use outside the app. It isn't a backup, and the app has no screen for importing it.
