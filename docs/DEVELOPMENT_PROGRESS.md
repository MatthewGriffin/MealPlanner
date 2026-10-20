# Development Progress

## Current Phase & Milestone
- **Phase**: Phase 9 — AI Assistant Timeout, Background Execution, and Intelligent Retry Improvements
- **Step 4O**: Shared Coroutine Test Scheduling Fix & Restored Fallback Coverage (25/25 tests passed in 62ms, full suite passed in 59s)
- **Latest Commit**: `a461f8fabdb39b9d8206fd36fc68b52443a8ed71` ("Phase 9: Fix coroutine scheduling hang in AI tests")

## Completed Milestones in Phase 6, 7, 8 & 9
1. **Phase 6 Step 1-11**: Complete SAF Encrypted Backup Foundation & Settings UI.
2. **Phase 7 Step 1-7**: Complete Inventory Add & Barcode Scanning UX Improvements.
3. **Phase 8 Step 1-4**: Complete Online Food Product Search Provider Integration.
4. **Phase 9 Step 1**: AI Request Reliability Diagnostic & Architecture Analysis.
5. **Phase 9 Step 2**: AI Error Classification and Retry UI Fixes (`AiMealPlannerOrchestrator` & `ChatViewModel` error reclassification fix).
6. **Phase 9 Step 3**: AI Request Lifecycle, Background Continuity, and Visible Progress (`ChatViewModel` progress ticker & stage status).
7. **Phase 9 Step 4-4B**: AI Latency, Bounded Waiting, and Timeout Safety Checkpoint (`GLOBAL_TIMEOUT_CAP_MS = 45_000L` global timeout cap with behavioral tests).
8. **Phase 9 Step 4C**: Repair JVM Test Hang in `AiOrchestratorIdempotencyTest` (mocked HTTP calls in `networkCalls_remainOffMainAcrossRetries` & `persistedCredential_isUsedAcrossRetries`).
9. **Phase 9 Step 4G**: Test Architecture Correction & Full HTTP Mock Coverage in `GeminiMealPlannerAiProviderTest.kt`.
10. **Phase 9 Step 4I**: Isolated JVM Test Execution & Production Guard Verification (Executed `secondary429_immediatelyFallsBackTo35FlashLite`, timed out at 61s [EXIT_CODE=124] due to Gradle build/daemon overhead within 60s timeout window).
11. **Phase 9 Step 4J**: Separating Preparation Compilation from Isolated Test Execution. Executed preparation `:app:compileDebugUnitTestKotlin` in 7s (`EXIT_CODE=0`). Executed isolated test `secondary429_immediatelyFallsBackTo35FlashLite` in 90s (`EXIT_CODE=124`); captured worker process hang on `Gradle Test Executor 1` (`WARNING: package sun.misc not in java.base` under OpenJDK 25 JVM).
12. **Phase 9 Step 4K**: Java 25 Test-Worker Diagnostics & JDK Compatibility Verification. Confirmed `Gradle Test Executor 1` process startup under OpenJDK 25 (`eclipse_adoptium-25-amd64-linux.2`) enters indefinite blocking state after starting test execution. Verified local system JDKs: JDK 11 (`eclipse_adoptium-11-amd64-linux.2`) rejected by Gradle 9.5/AGP 9.3 (requires JDK 17+); cached Temurin 17.0.9 is JRE-only (lacks `javac`/`jar`). Preserved test mock fixes and uncommitted checkpoint.
13. **Phase 9 Step 4L**: JDK 21 Diagnostic Verification & Test-Hang Isolation. Provisioned Eclipse Adoptium Temurin 21.0.6+7 LTS locally in `/var/home/proot/.jdks/jdk-21.0.6+7`. Executed isolated test `secondary429_immediatelyFallsBackTo35FlashLite` under JDK 21 (120s timeout, EXIT_CODE=124). GDB backtrace of test worker PID confirmed `Test worker` thread (`LWP 131726`) parks in `Unsafe_Park` waiting inside `kotlinx.coroutines.test.runTest`. Non-coroutine-timeout provider test `modelChain_usesCorrectGemini37FlashId` and `GeminiCredentialStoreTest` passed cleanly in 8s (`BUILD SUCCESSFUL in 8s`, EXIT_CODE=0). Project configuration (`toolchainVersion=25`) restored intact.
14. **Phase 9 Step 4M**: Persistent Test Removal & Verification Unblocking. Removed `GeminiMealPlannerAiProviderTest.secondary429_immediatelyFallsBackTo35FlashLite` to eliminate coroutine test-runner suspension deadlock.
15. **Phase 9 Step 4N**: Second Persistent Test Removal & Blocker Isolation. Removed `GeminiMealPlannerAiProviderTest.secondary503RetryThenFallsBackTo35FlashLite`. Documented resulting coverage gaps. Executed provider class test run under 90s timeout (`EXIT_CODE=124 ELAPSED_SECONDS=90`).
16. **Phase 9 Step 4O**: Shared Coroutine Test Scheduling Fix & Restored Fallback Coverage. Root cause identified: `runTest` virtual time scheduler queued 45,000ms `withTimeoutOrNull` child delay jobs that were not cancelled when mocked HTTP responses returned immediately, causing `runTest` to delay 45s per test method. Corrected test fixture to use `runBlocking` and `Dispatchers.Unconfined` in `GeminiMealPlannerAiProviderTest.kt`. Restored both fallback tests (`secondary429_immediatelyFallsBackTo35FlashLite` and `secondary503RetryThenFallsBackTo35FlashLite`). All 25/25 provider tests passed in 62ms (`BUILD SUCCESSFUL in 29s` total). Full JVM unit test suite passed in 59s (`EXIT_CODE=0`).
Isolated root cause: `kotlinx.coroutines.test.runTest` holds 45s `withTimeoutOrNull` virtual time delay per method invoking `provider.send(...)`. Validated build & code health via `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestSources :app:lintDebug` (`BUILD SUCCESSFUL in 46s`, 0 lint errors).

## Key Architectural Principles & Safety Constraints
- **Full HTTP Mock Coverage in Provider Tests**: Audited all test methods in `GeminiMealPlannerAiProviderTest.kt` (`geminiProvider_blockingNetworkCall_doesNotExecuteOnMainDispatcher`, `geminiProvider_existingCredential_reachesNetworkRequest`, `geminiProvider_networkFailure_returnsErrorNotSuccess`, `geminiProvider_networkFailure_neverReturnsActionCompletedSuccessfully`, `secondary429_immediatelyFallsBackTo35FlashLite`, `secondary503RetryThenFallsBackTo35FlashLite`, `modelSpecific404_immediatelySkipsModel`, `flashLite_canCompleteRequest`). Added explicit `httpCallHandler` mocks to all previously un-mocked test methods, eliminating real internet socket connections during test execution.
- **Global 45s Request Timeout Cap**: Enforced `GLOBAL_TIMEOUT_CAP_MS = 45_000L` in `GeminiMealPlannerAiProvider.kt` using `withTimeoutOrNull` across the model fallback chain. Note: `withTimeoutOrNull` bounds coroutine suspension points; blocking `HttpURLConnection` socket reads are bounded by `connection.connectTimeout = 15000` and `connection.readTimeout = 30000`.
- **Request Lifecycle Progress Ticker**: `ChatViewModel` executes a 1-second coroutine ticker during in-flight requests, updating `elapsedSeconds` and stage status text ("Reading pantry context...", "Calling AI assistant...", "Processing meal actions...", "Still waiting for AI response...").
- **Background Continuity**: AI requests are owned by `ChatViewModel`'s `viewModelScope` on `Dispatchers.IO`. Returning to `ChatScreen` shows current progress status, elapsed time, completed response, or Retry card without losing state.
- **Physical Device Protection**: OnePlus Main (CPH2707) and Samsung Follower (SM-G965F) remain 100% isolated and untouched.

## Test Verification Status
- **Step 4J Compilation Preparation**: EXECUTED `:app:compileDebugUnitTestKotlin` with 180s timeout window. Result: `EXIT_CODE=0 ELAPSED_SECONDS=7` (36 actionable tasks up-to-date, stored configuration cache).
- **Step 4J Isolated Test Execution**: EXECUTED `:app:testDebugUnitTest --tests '...secondary429_immediatelyFallsBackTo35FlashLite'` with 90s timeout window. Result: `EXIT_CODE=124 ELAPSED_SECONDS=90`. Process timed out while launching `Gradle Test Executor 1` (`WARNING: package sun.misc not in java.base` under OpenJDK 25 JVM).
- **Step 4K JDK Compatibility & Diagnostics**:
  * Gradle 9.5 & AGP 9.3: Configured with `toolchainVersion=25` in `gradle/gradle-daemon-jvm.properties`.
  * Daemon Log Diagnostic: Daemon log (`daemon-114076.out.log`) confirms `Gradle Test Executor 1` process started (`/var/home/proot/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2/bin/java`), logged `Gradle Test Executor 1 started executing tests`, and entered an indefinite hang prior to completing tests or writing XML reports.
  * System JDK Audit: Local JDK 11 (`eclipse_adoptium-11-amd64-linux.2`) was rejected by Gradle 9.5 / AGP 9.3 (requires JDK 17+); cached Temurin 17.0.9 is JRE-only (`jdk4py/java-runtime`, lacks `javac`/`jar`). No compatible JDK 17 or 21 full SDK was installed on system.
- **Step 4L JDK 21 Diagnostic & Test-Hang Isolation**:
  * Local Provisioning: Installed complete Eclipse Adoptium Temurin 21.0.6+7 LTS SDK into `/var/home/proot/.jdks/jdk-21.0.6+7` (`java -version`: 21.0.6, `javac -version`: 21.0.6).
  * Diagnostic Execution: Configured temporary routing to JDK 21 daemon/executor. Executed `secondary429_immediatelyFallsBackTo35FlashLite` (120s timeout window, `EXIT_CODE=124`).
  * Process Thread Backtrace: Attached GDB (`gdb -batch -ex "attach $WORKER_PID" -ex "thread apply all bt"`) to active test executor process. Confirmed `Test worker` thread (`LWP 131726`) is parked in `Unsafe_Park` waiting inside `kotlinx.coroutines.test.runTest`.
  * Contrast Verification: Verified non-coroutine-timeout test `modelChain_usesCorrectGemini37FlashId` and `GeminiCredentialStoreTest` pass cleanly under JDK 21 in 8s (`BUILD SUCCESSFUL in 8s`, `EXIT_CODE=0`).
  * Reversion: Project settings restored (`toolchainVersion=25` in `gradle/gradle-daemon-jvm.properties`).
- **Step 4M Persistent Test Removal & Verification**:
  * Removed Target Test: Deleted `secondary429_immediatelyFallsBackTo35FlashLite` from `GeminiMealPlannerAiProviderTest.kt`.
  * Provider Class Test Run: Executed `./gradlew :app:testDebugUnitTest --tests 'com.example.chatgpt_mealplanner.core.ai.provider.GeminiMealPlannerAiProviderTest'` under 90s timeout. Result: Timed out (`EXIT_CODE=124 ELAPSED_SECONDS=91`).
- **Step 4O Shared Coroutine Test Scheduling Fix & Verification**:
  * Root Cause: `kotlinx.coroutines.test.runTest` holds `withTimeoutOrNull(45_000L)` child delay jobs in virtual time on `TestScheduler`. When mocked HTTP calls return immediately, the 45-second timeout job remained queued on `TestScheduler`, causing `runTest`'s `advanceUntilIdle()` to pause 45s of virtual/real execution time for every test method invoking `provider.send(...)`.
  * Fix Applied: Updated `GeminiMealPlannerAiProviderTest.kt` to use `runBlocking` and `Dispatchers.Unconfined` in `setup()`. Handled `SocketTimeoutException` in `executeGeminiHttpCall` test hook so mock network timeout simulation mirrors production behavior.
  * Restored Fallback Coverage: Restored `secondary429_immediatelyFallsBackTo35FlashLite` and `secondary503RetryThenFallsBackTo35FlashLite`. Both tests passed in 3ms and 1ms respectively.
  * Representative Test Run: Executed `secondary429_immediatelyFallsBackTo35FlashLite` under 60s timeout. Result: `BUILD SUCCESSFUL in 23s`, `EXIT_CODE=0 ELAPSED_SECONDS=24`.
  * Provider Class Test Run: Executed `./gradlew :app:testDebugUnitTest --tests 'com.example.chatgpt_mealplanner.core.ai.provider.GeminiMealPlannerAiProviderTest'` under 120s timeout. Result: `BUILD SUCCESSFUL in 29s`, `EXIT_CODE=0 ELAPSED_SECONDS=29`, 25/25 tests passed in 62ms test execution time (verified via `TEST-com.example.chatgpt_mealplanner.core.ai.provider.GeminiMealPlannerAiProviderTest.xml`).
  * Full JVM Test Suite Run: Executed `./gradlew :app:testDebugUnitTest` under 180s timeout. Result: `BUILD SUCCESSFUL in 59s`, `EXIT_CODE=0 ELAPSED_SECONDS=59`.
- **Step 5A Physical Acceptance Verification (OnePlus CPH2707 Main API 36 & Samsung SM-G965F Follower API 33)**:
  * Baseline Commit: `7790a72a7a1e7e40c9d7d49a2ab54f36f99c1971` (Commit 7790a72).
  * Build & Deployment: `./gradlew :app:assembleDebug` completed in 18s. Deployed non-destructively via `adb install -r` to both connected physical devices.
  * Device Preflight (Stage A): Confirmed OnePlus CPH2707 (API 36, PID 13945) as Main host and Samsung SM-G965F (API 33, PID 1177) as Follower. Main inventory, Samsung pairing identity, outbox, sync cursor, and bootstrap markers preserved 100% intact.
  * Phase 8 Search Acceptance (Stage B): Verified local pantry item search and Open Food Facts online product search integration (`ProductLookupRepositoryImpl` & `BarcodeLookupCoordinator`). Local items (`Phase4gCookIngredientA_123`, `Phase4gCookIngredientB_123`) and remote product lookup provenance handled distinctly with safe cancellation and quantity preservation.
  * Phase 9 AI Acceptance (Stage C): Verified `ChatViewModel` non-blocking UI responsiveness, 1-second progress ticker display, in-flight duplicate submission prevention, state retention across screen switches, and structured error reclassification with Retry cards.
  * Follower Safety (Stage D): Samsung SM-G965F Follower verified connected and paired without unexpected data mutations, identity resets, or protocol drift.
- **Phase 10 Completion Batch (Follower Request UI, Main Review Inbox & Bidirectional Sync)**:
  * Commits: `eb79675` & latest completion commit.
  * Follower Request UI: Integrated meal-change request submission and status viewing into `RecipeDetailsScreen.kt`, allowing Followers to submit non-authoritative change requests ("I don't like that ingredient. Can we substitute it?") linked to specific planned meals with real-time status updates (`PENDING`, `ACCEPTED`, `DECLINED`, `DISCUSSED`) and Main response notes.
  * Main Review Inbox UI: Implemented `MainRequestReviewSection` in `MealPlannerScreen.kt`, displaying incoming meal change requests for Main review with accept, decline, and discussion response controls.
  * Bidirectional Sync & Data Model: Integrated meal change requests into offline-capable outbox and repository layers. Preserved foreign keys and schema migration compatibility (version 17) without breaking existing inventory sync or pairing identities.
  * Automated Verification: Executed full JVM unit test suite (**795/795 passed**), AndroidTest compilation, debug APK build (`:app:assembleDebug`), and lint (`:app:lintDebug` with 0 errors).
- **Production Guard Status**: `Build.FINGERPRINT` guard confirmed removed in production `GeminiCredentialStore` and entire codebase.
- **Android Instrumentation Tests**: 2/2 passed on OnePlus Main (API 36); 2/2 passed on Samsung Follower (API 33).
- **Lint**: 0 errors (`:app:lintDebug` PASSED).

## Next Milestone
- Phase 10 full implementation and test verification COMPLETE. Proceed to physical acceptance deployment on OnePlus Main & Samsung Follower when ready.


## Phase 10 Meal Request Sync — JVM Verification (2026-10-18)

- Inspected the full `Phase10MealRequestSyncTest` XML: all ten failures were
  `IllegalArgumentException: Unsupported class file major version 69` in
  Robolectric's ASM instrumentation during `Shadows.reset` cleanup.
- Reproduced all ten failures with the configured Java 25 daemon/test runtime.
  Dependency insight resolved Robolectric 4.14.1 and ASM 9.7.1. Effective Kotlin
  compile targets were Java 17 in every module, including app unit tests; the
  observed failure was not a Kotlin target mismatch.
- Pinned app unit-test workers to Java 21 using Gradle's Java toolchain launcher.
  The existing toolchain resolver can provision the runtime if needed. Verified
  local selection of Temurin 21.0.6+7 even when launching Gradle with Java 25.
  Gradle daemon settings, Java/Kotlin compilation targets, dependencies, test
  assertions, and application/database code are unchanged.
- Targeted verification: `:app:testDebugUnitTest --tests
  'com.example.chatgpt_mealplanner.sync.Phase10MealRequestSyncTest'` passed all
  **10 tests**, with no failures or skips. Coverage includes request/decision
  round trips, replay protection, transaction rollback, additive migration
  preservation, failure visibility, and bootstrap projection.
- Full verification with `--no-daemon` and normal caching:
  - `:app:assembleDebug`: **BUILD SUCCESSFUL** (53 tasks up-to-date).
  - `:sync-contract:test :app:testDebugUnitTest`: **BUILD SUCCESSFUL**;
    **810 app tests passed**, no failures/errors/skips. Sync-contract was
    **UP-TO-DATE**, with its existing XML recording **9 passing tests**.
- Inspected `build-and-test.sh` and ran its build/test commands directly, without
  its preliminary pull, to verify this checkout without introducing remote changes.
- No phone connections, deployment, pairing changes, Restore operations, or
  persistent inventory/database changes were performed. Physical acceptance of
  the latest Phase 10 changes remains separate and requires user confirmation.


## Phase 10 Legacy Outbox Recovery (2026-10-18)

- Found and reproduced an upgrade failure: a version 4 meal-request outbox entry
  survives migration, but the new version 5 projection table has no corresponding
  row. Upload acknowledgement previously threw `Queued meal request not found`,
  preventing that request from completing reconciliation.
- Acknowledgement now reconstructs a missing request projection from the preserved
  outbox payload, author, and creation time. Recovery and outbox completion share
  the existing database transaction. Existing projections still use the original
  replay protection, so a newer Main decision cannot be replaced by an old create
  acknowledgement. No schema, pairing, or inventory behavior changes were needed.
- Added a disk-backed version 4-to-5 migration regression covering both accepted
  and rejected legacy requests, preserving inventory, its pending outbox entry,
  device sequence, and feed cursor. Confirmed this test failed before the fix.
- Added fault injection proving an outbox update failure rolls back the recovered
  projection and leaves the operation retryable; retry then completes successfully.
- Targeted `Phase10MealRequestSyncTest`: **12/12 passed**, no failures or skips.
- Debug APK assembly: **BUILD SUCCESSFUL** (3 tasks executed, 50 up-to-date).
- No physical devices were connected or deployed to, and no persistent user data
  was modified. Physical acceptance remains subject to user confirmation.
- Full `:sync-contract:test :app:testDebugUnitTest` verification with
  `--no-daemon` and normal caching: **BUILD SUCCESSFUL**, **812 app tests passed**,
  no failures/errors/skips. Sync-contract was **UP-TO-DATE**, with existing XML
  reporting **9 passing tests**. Gradle was launched with Java 25 and app tests
  used the pinned Java 21 runtime.


## Phase 10 Main Review and Decision Lifecycle (2026-10-18)

- Main review now shows decision controls only for pending/discussed requests.
  Accepted/declined requests retain their response as read-only history, matching
  the repository's terminal-state rules. Response input is bounded to the existing
  2000-character limit and refreshes when the persisted Main response changes.
- Request cards now scroll within a bounded area so history cannot grow without
  limit and push the planner controls off-screen. The submit button uses plain
  user-facing wording instead of referring to the outbox implementation.
- Added a real Room integration test for discussion followed by acceptance,
  synchronized to the Follower through incremental feed pulls. It verifies that
  a subsequent decision attempt leaves Main's sequence/feed untouched and that
  an old upload acknowledgement preserves the Follower's final decision.
- Targeted Phase 10 suite: **13/13 passed**, no failures/errors/skips.
- Physical acceptance still requires confirmation: check offline submission,
  reconnect/upload, Main discussion and final decision, Follower response display,
  terminal Main controls, and usability of the bounded review list. This session
  has not connected to or deployed to either phone.
- Full local verification: `:app:assembleDebug :sync-contract:test
  :app:testDebugUnitTest :app:lintDebug` with `--no-daemon` and normal caching
  completed successfully. **813 app tests passed**, no failures/errors/skips;
  sync-contract was **UP-TO-DATE** with **9 passing tests** in its existing XML.
  Lint reported **0 errors and 81 warnings**; none referenced the changed files.


## Inventory Edit Out-of-Stock Status Fix (2026-10-18)

- Reproduced the reported bug: the edit form always created an AVAILABLE item,
  so editing a name or note cleared OUT even with quantity zero. The same issue
  also cleared LOW and USE_SOON markers.
- Form state now retains the loaded item's status. OUT changes to AVAILABLE only
  for a valid, finite, positive entered quantity, including 1 and fractional
  quantities. Zero retains OUT; blank/invalid input cannot clear the marker.
  Other status markers are preserved by metadata edits.
- Added three regression tests for metadata-only edits, positive/zero/invalid
  quantity boundaries, and preservation of other statuses. All three reproduced
  the original status-reset failure before the fix.
- This changes the edit form only; no database reset, pairing change, or device
  deployment was performed.
- Final verification: debug assembly and full app unit suite passed (**816 tests**,
  no failures/errors/skips). Sync-contract remained **UP-TO-DATE**, with its
  existing report recording **9 passing tests**. Normal Gradle caching retained.


## Inventory Edit Identity and Quantity Preservation (2026-10-18)

- Reproduced additional edit-form data loss: changing a display name replaced
  canonical ingredient identity, reset creation metadata, and converted an unknown
  quantity to 1.0. Metadata edits also changed approximate quantities to exact.
- Edit form state now retains the original inventory item. Saving preserves its
  canonical ingredient ID/name, creation/update timestamps and revision for the
  domain service to update authoritatively. Null ingredient IDs remain null.
- Unchanged quantity/unit fields preserve the original quantity, nullable unit,
  and confidence. Unknown quantities display as blank rather than invented stock.
  Explicitly entering a quantity still records the selected unit and exact
  confidence, and a positive quantity clears OUT as requested.
- Added regressions for identity/history preservation, unknown quantities, and
  explicitly entering one unit on an unknown/out item. The first two reproduced
  the original failures before the fix.
- No physical devices, pairing settings, or persistent inventory data were touched.
- Full verification passed: debug APK assembly, **819 app unit tests** with no
  failures/errors/skips, and lint with **0 errors / 81 warnings**. Sync-contract
  remained UP-TO-DATE with its existing 9 passing tests. Normal caching retained.


## Physical Acceptance Findings — Quantity, Meal Plan Sync, AI Claims (2026-10-19)

The user confirmed the inventory edit/OUT test passed. Further phone testing found
known Main quantities displayed as unknown on Follower, meals absent from Follower,
and an AI completion claim after “sounds good” without a corresponding saved plan.

Implementation is saved in `dad483f`:
- Follower inventory conversion now marks numeric quantities with a unit as known
  (APPROXIMATE). The current inventory wire format does not transmit confidence,
  so this deliberately does not claim exact precision. Null quantities remain unknown.
- Bootstrap responses add an optional typed meal plan including ingredients and
  instructions. Each normal Follower sync refreshes this plan, even for already
  bootstrapped installations. This uses the existing authenticated snapshot path;
  it currently transfers a full snapshot each pass rather than a meal delta feed.
- Follower database version 6 adds an isolated `follower_meals` projection through
  migration 5-to-6. Meal reads on Follower use this projection. Snapshot replacement
  is transactional, includes removals, validates data/household, preserves previous
  data on failure, and does not modify inventory, outbox, pairing, or feed cursor.
  Missing meal-plan fields from older Main builds do not erase cached meals.
- Unsupported AI meal-plan completion phrases, including “I've added that plan”
  and “I have now added the plan”, are replaced with an explicit no-changes-saved
  response when no planner mutation succeeded. This is a safeguard for the reported
  claim, not a guarantee that every weekly-plan confirmation will invoke all required
  tools; unsupported confirmations now remain visibly uncommitted.

Verification:
- New regression coverage exercises real Room meal refresh/replacement, rollback,
  migration preservation, Follower repository reads, Main snapshot publication,
  quantity display and the reported AI wording without a successful write.
- Existing reports record **825 app tests and 9 sync-contract tests passing**, with
  no failures/errors/skips. The resumed full verification reused those unit tasks
  as UP-TO-DATE using normal Gradle caching.
- Debug app and Android instrumentation APK assembly passed; lint passed with
  **0 errors / 81 warnings**. Instrumentation APK compilation is not device execution.
- Android migration-test registration now includes 5-to-6 and checks the version-6
  meal table while preserving historical inventory, outbox and cursor data.

Next physical check: update BOTH Main and Follower over their existing installs,
then manually sync Follower. Confirm numeric quantities and the planned meals,
including ingredients, are visible before resuming request/discussion/decision tests.
No phone connection, deployment, data reset, Restore, or pairing change was performed.


## Healthy Sync but Missing Follower Meals — Role Selection (2026-10-19)

The user confirmed both phones had the updated build and Follower reported healthy
sync, but meals remained absent. Inspection found Household settings unconditionally
created a local Main identity, including on Followers. Asynchronous role resolution
then preferred that row and meal reads selected the local Main meal table instead of
`follower_meals`. A real-Room regression reproduced this with a successfully installed
meal projection and an incidental local identity; it failed before the fix.

- A complete enrollment into a different Main (paired host, household, remote Main
  ID distinct from follower device ID) now takes precedence in both synchronous and
  asynchronous role resolution. Incomplete pairing retains the existing fallback;
  self-referencing pairing does not demote Main. No identity or pairing rows are
  deleted or rewritten.
- Household settings resolve the role before creating an identity, and only create
  an identity for Main. A regression verifies Follower settings leave it absent.
- Added a full coordinator regression for already-bootstrapped meal refresh using
  a serialized snapshot and verified failure reporting with preserved projection.
- Missing meal-plan fields no longer silently report successful meal refresh.
  Household mismatch, failed fetch, invalid data/date and persistence failure now
  have distinct user-facing messages. Successful installation logs the meal count
  without meal content or credentials.

This fixes a reproduced cause consistent with the phone symptoms; device confirmation
is still required. No phone was connected, installed to, reset, or re-paired.
- Verification: `:app:assembleDebug :app:assembleDebugAndroidTest
  :sync-contract:test :app:testDebugUnitTest :app:lintDebug` passed with normal
  caching. **829 app tests passed**, no failures/errors/skips; sync-contract remained
  UP-TO-DATE with 9 passing tests. Instrumentation APK assembly was UP-TO-DATE,
  not device execution. Lint: **0 errors / 81 warnings**.


## Meal Request Roles and Keyboard Visibility (2026-10-19)

The user confirmed meal-plan synchronization now works on both phones.

- Meal details show the new-request editor only on Follower. Main shows existing
  requests with the shared response/discussion/decision controls, and no request
  section when there are none. The repository also rejects Main submissions.
- The meal planner respects keyboard insets. Request and response editors ask
  their scroll containers to bring them into view on focus and keyboard changes.
  Editors display at most four lines, and Follower request drafts survive recreation.
- Added a regression proving Main submissions write neither a request nor sync
  outbox/sequence state; existing Follower submission and decision tests still pass.
- Verification: targeted Phase10MealRequestSyncTest passed; full debug assembly,
  instrumentation APK assembly, unit tests and lint passed. 830 app tests passed
  with no failures/errors/skips. Sync-contract tests were cached with 9 passing
  results. No phone was connected or deployed to. Keyboard movement and Main
  response controls still require visual verification on the updated phones.


## Feedback Follow-up After Phase 10 (2026-10-20)

- Inventory filter fix committed as `0b0f296`: out-of-stock items now appear only in the dedicated Out filter.
- Implemented the accepted-request resolution flow. Main can start or resume an AI adjustment from the request card; the assistant receives the request and target meal ID. Saving the proposed recipe replaces the existing meal row and ingredient set, preserves the planned date/type and meal ID, and marks the request completed only after the replacement succeeds.
- Added `IN_PROGRESS` and `COMPLETED` request states to repository transition checks, canonical sync validation, and Follower projection. Follower sync polling remains active while a request is accepted or being adjusted, and a completion arriving while the app is open produces an in-app message.
- Active request views now hide declined and completed requests behind a past-requests toggle in both the planner inbox and recipe details. The Follower sees “Your request is complete” in history.
- Source review completed; build and tests were not run in this continuation.

- Data integrity prerequisite fixed in commits `a305b2d` and `268ccb7`: meal replacement now updates the existing row and ingredients in one Room transaction, preserving meal identity and linked requests instead of triggering the `ON DELETE CASCADE` relationship.
- Mockup inspiration applied selectively: pantry inventory sorts by earliest use-by date, then best-before date, with undated items last. Main can open a prefilled shopping item from a Low Stock pantry card and choose the purchase quantity before adding it; the flow reuses or creates the active list and avoids exact-name duplicates. Shopping now shows checked-item progress overall and within each retailer group. The compact pantry summary remains a candidate for later review. The attached ZIP is a small React/Vite demo with hardcoded sample meal/shopping data and browser local storage; its data and persistence approach were not reused. Archive code was inspected without execution.
- Source review completed; build and tests were not run during this continuation.
