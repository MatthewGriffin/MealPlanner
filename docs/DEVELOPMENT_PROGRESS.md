# Development Progress

## Current Phase & Milestone
- **Phase**: Phase 9 — AI Assistant Timeout, Background Execution, and Intelligent Retry Improvements
- **Step 1**: AI Request Reliability Diagnostic & Architecture Analysis
- **Latest Commit**: `46d40809a048e55981a65e8a21d673bf5b5fb32f` ("Phase 8: Complete hybrid product search UI")

## Completed Milestones in Phase 6, 7, 8 & 9
1. **Phase 6 Step 1-11**: Complete SAF Encrypted Backup Foundation & Settings UI.
2. **Phase 7 Step 1-7**: Complete Inventory Add & Barcode Scanning UX Improvements.
3. **Phase 8 Step 1-4**: Complete Online Food Product Search Provider Integration.
4. **Phase 9 Step 1**: AI Request Reliability Diagnostic & Architecture Analysis.

## Phase 9 Step 1 Diagnostic Findings
- **Root Cause of Missing Retry Button ("Gemini is currently unavailable")**:
  - In `AiMealPlannerOrchestrator.kt` (lines 476/511), when `provider.send()` returns an error (HTTP 503, 429, or network timeout) AFTER local DB context reads succeed (`readSucceeded == true`), the orchestrator converts the transient error into an assistant chat message `"Gemini is temporarily unavailable. Your meal-plan data was not changed."` and returns `AiOrchestrationResult.Final`!
  - `ChatViewModel` receives `AiOrchestrationResult.Final` and treats it as a SUCCESSFUL completion (`ChatRequestState.SUCCEEDED`), clearing `retainedUserMessage = null`.
  - Because `retainedUserMessage == null`, `ChatUiState.isRetryAvailable` evaluates to `false` and no Retry button is displayed!
- **Root Cause of Extended Processing Delays**:
  - `GeminiMealPlannerAiProvider` configures 15s connect timeout + 30s read timeout per HTTP request.
  - Model fallback chain (`gemini-3.7-flash` -> `gemini-3.6-flash` -> `gemini-3.5-flash-lite`) permits up to 3 global attempts with backoff delays up to 5000ms.
  - Multiple attempts * 30s readTimeout can stack up to 100+ seconds total waiting time while `isLoading == true` with no progress timer or stage feedback.
- **Root Cause of Backgrounding Behavior**:
  - `ChatViewModel` owns requests in `viewModelScope.launch`. Switching apps puts `MainActivity` into STOPPED state, but `viewModelScope` remains active unless Android kills the process due to low memory.
  - If process survives backgrounding, the request finishes in background; if process is killed, in-flight state is lost without recovery.

## Proposed Phase 9 Implementation Steps
- **Phase 9 Step 2**: Error Classification & Retry UI Fixes (`AiMealPlannerOrchestrator` & `ChatViewModel` - stop converting transient errors into fake assistant messages; surface Retry card for all transient AI errors).
- **Phase 9 Step 3**: Request Lifecycle & Backgrounding State Persistence (`ChatRequestTracker`, 45s global timeout cap, progress timer, process recreation recovery).
- **Phase 9 Step 4**: Latency Optimization & Idempotency Safeguards (pruning context, tool call deduplication).
- **Phase 9 Step 5**: Physical Device Verification on OnePlus Main & Samsung Follower with Final Phase 9 Checkpoint Commit.

## Key Architectural Principles & Safety Constraints
- **Zero Silent Error Reclassification**: Transient AI errors MUST return `AiOrchestrationResult.Error` so `ChatViewModel` sets `FAILED_RETRYABLE` with `isRetryAvailable == true`.
- **Idempotency**: Retried messages verify `PendingAiMutationEntity` to prevent duplicate meal creations or inventory deductions.
- **Physical Device Protection**: OnePlus Main (CPH2707) and Samsung Follower (SM-G965F) remain 100% isolated and untouched.

## Test Evidence
- **JVM Unit Tests**: 788 passed, 0 failed, 0 skipped.
- **Android Instrumentation Tests**: 2/2 passed on OnePlus CPH2707 Main (API 36) in 5.038s; 2/2 passed on Samsung SM-G965F Follower (API 33) in 7.205s.
- **Lint**: 0 errors (`:app:lintDebug` PASSED).

## Next Milestone
- Proceed to **Phase 9 Step 2** (Error Classification & Retry UI Fixes).
