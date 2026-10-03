# Backup & Export Specification (`pantry-planner-export-v1`)

This document defines the portable backup format, exported datasets, and atomic import/rollback procedure in Pantry Planner.

---

## 1. Exported Datasets (Authoritative User Data)

- **Exported**: Inventory items, transactions, meals, ingredients, cooked history, leftovers, shopping lists, requirement sources, staple rules, barcode mappings, retail mappings (`CONFIRMED_MATCH` / `REJECTED_MATCH`), manual retail offers, user settings, chat history (optional).
- **Excluded**: Provider continuation tokens, backend secret tokens, API keys, AI execution logs, derived live retail caches.

---

## 2. Multi-Phase Import Procedure

1. **Phase A (Validate Everything)**: Validates schema version (`pantry-planner-export-v1`), non-negative quantities, enum bounds, and foreign key references.
2. **Phase B (Recovery Snapshot)**: Creates an internal recovery snapshot of existing local data.
3. **Phase C (Room Restoration)**: Runs an atomic Room transaction clearing tables in FK order and inserting imported records.
4. **Phase D (Settings Restoration)**: Writes DataStore settings. Executes compensating rollback from Phase B snapshot if restoration fails.
