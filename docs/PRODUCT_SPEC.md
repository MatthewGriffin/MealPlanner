# Product Specification: Pantry Planner

## 1. Product Goal
Pantry Planner is a local-first Android application designed for household food inventory management, AI-assisted meal planning, recipe generation, barcode product entry, shopping list generation, food waste reduction, and multi-store grocery price optimization.

### Core Goals
1. Track all household food inventory (fridge, freezer, cupboard, bakery, sauces, snacks, meal kits).
2. Support exact, approximate, and unknown quantities without inventing fake numbers.
3. Quick product entry via CameraX + ML Kit barcode scanning and database lookup.
4. Fast manual item entry when barcode lookup fails.
5. Track use-by / best-before dates, opened state, low stock, out of stock, and discarded items.
6. Chat naturally with a provider-neutral AI meal planner ("What's for dinner?", "Make tonight low carb", "I don't have mushrooms").
7. Generate weekly meal plans (Monday to Sunday) and manage batch cooking and leftovers.
8. Automatically deduct ingredients ONLY when meals are confirmed cooked (never on planning/suggestion).
9. Generate waste-aware grocery lists based on planned recipes, owned items, package sizes, and staple rules.
10. Multi-store grocery price comparison (Asda, Iceland / The Food Warehouse) with convenience thresholds to prevent multi-store trips for trivial savings.
11. Support low-carbohydrate meal goals and custom dietary preferences.
12. Maintain strict local database authority over AI conversation history.

Default Household Size: 2 people.

---

## 2. Core Architecture Principle
**THE DATABASE IS THE SOURCE OF TRUTH.**

- The AI is a reasoning and prompt engine, NOT the source of truth.
- Persistent state (inventory, transactions, meals, leftovers, shopping lists, user preferences) lives strictly in the SQLite/Room database.
- Database state overrides AI conversation memory at all times.
- Explicit user corrections immediately update inventory database state.

---

## 3. Technology Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose, Material 3
- **Architecture**: MVVM, Unidirectional Data Flow (UDF), Repository Pattern, Domain Services
- **Persistence**: Room (SQLite), DataStore (Preferences)
- **Dependency Injection**: Hilt
- **Async**: Kotlin Coroutines, Flow / StateFlow
- **Barcode Scanning**: CameraX, ML Kit Barcode Scanning
- **Background Tasks**: WorkManager
- **Networking & Serialization**: Retrofit / OkHttp, kotlinx.serialization
- **Testing**: JUnit4, Room in-memory testing, ViewModel/Repository tests
- **Min SDK**: 30 (Android 11) | **Target/Compile SDK**: 37

---

## 4. Key Capabilities & System Guarantees
- **Local-First & Offline Functionality**: Viewing/editing inventory, marking items out, viewing meal plans, checking recipes, marking cooked, and managing shopping lists work completely offline.
- **Provider-Neutral AI Architecture**: AI adapter interface (`MealPlannerAiProvider`) abstracts backend models (OpenAI, Gemini, Anthropic). No AI vendor SDKs are imported into core domain logic.
- **Security**: No secret API keys embedded in the Android APK. Requests route through a secure backend proxy.
- **Atomic Transactions**: Marking a meal cooked executes ingredient deductions, transaction logs, and leftover creation inside a single atomic Room database transaction.
