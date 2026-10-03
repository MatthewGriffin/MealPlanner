# Phase 7B: Retailer Adapter Architecture & Parser Precedence

This document specifies the Ktor backend retailer stack, JSON-LD vs. HTML parser precedence, Money price parsing, and pack size normalization.

---

## 1. Parser Precedence Rules (`IcelandProductParser`)

1. Valid structured Schema.org JSON-LD microdata for explicit fields.
2. Explicit product card HTML DOM attributes.
3. Narrow HTML text fallback parsing.
4. `UNKNOWN` / absent.

*Conflict Warning*: If JSON-LD price and HTML DOM price conflict, a parse conflict warning is emitted, and structured JSON-LD price is preserved.

---

## 2. Rate Limiting Policy

- `ICELAND_MIN_REQUEST_INTERVAL_MS` = `1000L` (1 request per second).
- `ICELAND_MAX_REQUESTS_PER_MINUTE` = `60`.
- User-Agent header: `PantryPlannerRetailAdapter/1.0`.
- Documented as Pantry Planner internal request policy (not retailer permission).
