# AI Provider Architecture

This document specifies the provider-neutral runtime AI integration strategy for Pantry Planner.

---

## 1. Provider Neutrality
- Gemini is used to develop the app inside Android Studio.
- The runtime AI in the mobile client MUST BE provider-neutral.
- Runtime AI providers may include OpenAI, Gemini, Anthropic, or custom local/cloud LLMs.
- Neither the UI components nor the core domain/inventory packages import vendor-specific SDKs.

---

## 2. Core Abstraction (`MealPlannerAiProvider`)

```kotlin
interface MealPlannerAiProvider {
    suspend fun chat(
        request: MealPlannerRequest
    ): Result<MealPlannerResponse>

    suspend fun continueWithToolResults(
        conversationId: String,
        toolResults: List<AiToolResult>
    ): Result<MealPlannerResponse>
}
```

---

## 3. Provider Adapters & Settings
Supported providers represented via `AiProvider` enum:

```kotlin
enum class AiProvider {
    OPENAI,
    GEMINI,
    ANTHROPIC
}

data class AiSettings(
    val provider: AiProvider,
    val modelName: String,
    val reasoningLevel: String = "balanced"
)
```

Settings are stored in Android `DataStore` and managed via Settings UI.

---

## 4. Secure Backend Proxy Architecture
To avoid embedding secret API keys inside the compiled Android APK:

```
Android App (MealPlannerAiProvider)
       │
       ▼ (HTTPS / Signed App Check)
Backend Proxy Server (Firebase Functions / Cloud Run / Supabase Edge)
       │
       ▼ (Authorized API Key)
External AI Provider (OpenAI / Gemini / Anthropic API)
```

---

## 5. Context Builder (`AiContextBuilder`)
To avoid sending entire uncompressed chat history on every turn, `AiContextBuilder` constructs a tight, structured context payload:
- Today's date and day of week
- Household size (default: 2)
- Relevant user preferences (frozen veg priority, low-carb mode, garlic powder preference)
- 14-day meal history (to enforce repetition rules)
- Current items marked `USE_SOON` or nearing expiry
- Today's currently planned meals
- Current available leftovers
- Shopping list state

---

## 6. Response Contract & Tool Calling Flow
1. User sends message -> `AiContextBuilder` creates context.
2. `MealPlannerAiProvider` calls backend proxy with prompt + available tools schema.
3. Proxy forwards request to LLM.
4. If LLM responds with tool calls -> `AiToolDispatcher` executes domain tool methods locally.
5. `MealPlannerAiProvider.continueWithToolResults()` returns tool execution outputs back to proxy.
6. Proxy/LLM returns structured `MealPlannerResponse`:
   - `message`: User-facing explanation
   - `meal`: Structured recipe object (if recipe generated)
   - `suggestedActions`: Native Compose UI action cards (`MARK_COOKED`, `MAKE_LOW_CARB`, `ADD_MISSING_TO_SHOPPING`, etc.)
