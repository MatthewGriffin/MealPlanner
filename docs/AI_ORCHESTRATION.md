# Phase 5: Provider-Neutral AI Orchestration Layer & Mock AI Provider

This document specifies the provider-neutral architecture, safety boundaries, tool loop orchestrator, mutation authorization model, and recipe validation rules in Pantry Planner.

---

## 1. Core Principles & Boundaries

1. **Provider Neutrality**: No vendor SDK (OpenAI, Gemini, Anthropic) is coupled into domain or UI packages.
2. **Authoritative State Boundary**: The AI model is **never** the source of truth for inventory quantities, meal status, or shopping arithmetic. It operates through domain services.
3. **No Direct Database Access**: The AI model cannot write raw SQL, access Room DAOs, or mutate entities directly.
4. **Fine-Grained Write Authorization (`AuthorizedMutationScope`)**:
   - `UserMutationIntentResolver` derives permission scopes strictly from the user's turn message.
   - WRITE tools must satisfy both mutation type and parameter scope (e.g. user stating "No mushrooms left" authorizes `MUSHROOM` out, but rejects `CHICKEN` out).
5. **Proposal vs. Action Separation**:
   - Asking "What's for dinner?" produces a `MealProposal` returned in UI without mutating Room.
   - Database writes occur **only** when the user explicitly taps "Save to Plan".
6. **Confirmation Flow for Cooking**:
   - `PREPARE_MARK_MEAL_COOKED` returns cooking confirmation details to UI (`CookConfirmationPayload`).
   - The AI dispatcher does **not** deduct inventory directly. Actual inventory deduction occurs when the user confirms quantities in `CookConfirmationDialog`.

---

## 2. Canonical Tool Registry (`AiToolId`)

All tools are enumerated in a single source of truth (`AiToolId`):

| Tool ID | Classification | Purpose |
|---|---|---|
| `GET_INVENTORY_SUMMARY` | `READ` | Returns inventory counts and storage summaries |
| `GET_INVENTORY_FOR_INGREDIENT` | `READ` | Queries usable inventory lots for an ingredient |
| `GET_USABLE_INVENTORY` | `READ` | Returns usable items filtered by location/category |
| `GET_LOW_STOCK_ITEMS` | `READ` | Returns low stock / staple threshold breach items |
| `GET_USE_SOON_ITEMS` | `READ` | Returns items nearing expiry / use-soon status |
| `GET_RECENT_COOKED_MEALS` | `READ` | Returns 14-day cooked history for repetition checks |
| `GET_UPCOMING_PLANNED_MEALS` | `READ` | Returns upcoming planned meals |
| `GET_MEAL_DETAILS` | `READ` | Returns meal details and ingredients |
| `GET_AVAILABLE_LEFTOVERS` | `READ` | Returns available leftover records |
| `GET_ACTIVE_SHOPPING_LIST` | `READ` | Returns active shopping list items |
| `CALCULATE_SHOPPING_REQUIREMENTS` | `READ` | Runs deterministic shopping calculation service |
| `MOVE_PLANNED_MEAL` | `WRITE_EXPLICIT` | Moves planned meal to a new date |
| `CANCEL_PLANNED_MEAL` | `WRITE_EXPLICIT` | Marks a planned meal as CANCELLED |
| `RESTORE_MEAL_TO_PLANNED` | `WRITE_EXPLICIT` | Restores a SKIPPED meal back to PLANNED |
| `PREPARE_MARK_MEAL_COOKED` | `WRITE_CONFIRMATION_REQUIRED` | Prepares cook confirmation card for UI |
| `MARK_INVENTORY_LOT_OUT` | `WRITE_EXPLICIT` | Sets physical lot to OUT |
| `MARK_ALL_INVENTORY_FOR_CANONICAL_INGREDIENT_OUT` | `WRITE_EXPLICIT` | Sets all lots for canonical ingredient to OUT |
| `ADJUST_INVENTORY_QUANTITY` | `WRITE_EXPLICIT` | Adjusts physical lot quantity |
| `RESOLVE_INVENTORY_LOT_UNCERTAINTY` | `WRITE_EXPLICIT` | Resolves uncertain lot quantity |
| `REGENERATE_SHOPPING_LIST` | `WRITE_EXPLICIT` | Triggers atomic shopping list regeneration |
| `ADD_MANUAL_SHOPPING_ITEM` | `WRITE_EXPLICIT` | Adds a manual item to shopping list |

---

## 3. Idempotency & Replay Safety

- Idempotency key: `conversationId + toolCallId`.
- Execution records logged in `ai_tool_execution_logs` table with `UNIQUE(conversationId, toolCallId)`.
- Replaying a successful write tool call returns the cached result payload without re-executing domain service mutations.

---

## 4. Room Migration `MIGRATION_3_4`

Room database version upgraded from 3 to 4:
- Created `chat_conversations`, `chat_messages`, `ai_tool_execution_logs`.
- Foreign key cascading deletes messages/logs when conversation is deleted.
- Chat deletion has **zero impact** on inventory, meals, shopping, or leftovers.
