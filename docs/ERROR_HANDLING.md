# Error Taxonomy & Degraded-Mode Specification

This document defines user-safe error mappings, recovery flows, and non-blocking degraded launch behavior.

---

## 1. Error Mapping Table

| Internal Code | User-Facing Message | Recovery Action |
|---|---|---|
| `NETWORK_UNAVAILABLE` | *"Network connection unavailable. Using cached local data."* | Retry on network restoration |
| `AI_BACKEND_OFFLINE` | *"AI assistant is temporarily offline. Local features remain available."* | Banner notification; local domain active |
| `RETAIL_SOURCE_FORMAT_CHANGED` | *"Retailer page format changed. Using cached price observations."* | Fallback to cached/manual offers |
| `DATABASE_CORRUPT` | *"Local database error. Tap to restore from backup."* | Directs to `DatabaseRecoveryScreen` |
| `UNAUTHORIZED_WRITE_ACTION` | *"I didn't make that change because it wasn't explicitly requested."* | User alert |
