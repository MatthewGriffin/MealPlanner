# AI Data Flow & Privacy Specification

This document specifies the privacy boundary, data transport policy, and three retention types in Pantry Planner.

---

## 1. Three Distinct Retention Types

1. **Local App Data (Android Room Database)**:
   - Authoritative domain state (inventory items, meal plan, shopping lists, leftovers).
   - Conversational UI history in `chat_conversations` and `chat_messages`.
   - Tool execution audit logs in `ai_tool_execution_logs`.
   - Deleting a chat conversation purges messages and tool execution logs, leaving domain inventory/meals/shopping 100% untouched.

2. **Backend Temporary State (Ktor Proxy Cache)**:
   - Short-lived, in-memory `OpenAiContinuationState` cache for tool execution turns.
   - Bounded by 10-minute TTL and authenticated user session isolation.
   - Stores prior response output items required for stateless tool continuation rounds.
   - Never stored permanently on backend disk; invalidated immediately when turn completes.

3. **OpenAI Processing & Retention Controls**:
   - `store: false` is explicitly sent on **every** OpenAI API request.
   - OpenAI API customer data is not used to train OpenAI models by default.
   - `store: false` controls OpenAI Responses API application-state persistence.
   - Abuse-monitoring retention applies according to OpenAI account/project controls.

---

## 2. Minimal Data Transport Boundary

- Only structured, relevant context is sent to the backend (`AiContextPayload`).
- API keys, passwords, PII, and raw database schemas are **never** transmitted.
- Requests enforce HTTPS transport; cleartext HTTP is prohibited in production.
