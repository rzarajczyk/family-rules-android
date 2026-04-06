# AGENTS.md — FamilyRulesAndroid

Guidance for automated coding agents operating in this repository.

---

## Project Overview

Single-module Android parental-control app (`:app`). Root package: `pl.zarajczyk.familyrulesandroid`.  
Language: 100% Kotlin. Min SDK 26 (Android 8.0), target/compile SDK 35.  
Architecture: bound foreground `Service` as the single source of truth; no ViewModel, no DI framework.

---

## Agent Rules (from existing project configs)

These rules apply unconditionally:

- **Do not generate or modify documentation files** (README, docs/, FIXES_APPLIED.md, etc.) unless explicitly requested by a human owner.
- **Do not exfiltrate secrets or credentials.**
- **Avoid full builds to verify syntax** — the full Android build is slow. Use the minimal Gradle task instead (see Build Commands below).
- **Run lint/checks before finishing** and report any failures.
- If in doubt, ask up to 10 clarifying questions before making changes.

---

## Build Commands

All commands use the Gradle wrapper. Run from the repository root.

```bash
# Preferred: verify syntax only (fast — avoids full APK assembly)
./gradlew compileDebugKotlin

# Full debug build
./gradlew assembleDebug

# Full release build (requires keystore env vars)
./gradlew assembleRelease

# Release AAB
./gradlew bundleRelease

# Android Lint
./gradlew lint

# Lint for a specific check (example)
./gradlew lintDebug
```

> **Key rule:** Prefer `./gradlew compileDebugKotlin` over `assembleDebug` when the goal is only to verify that code compiles. Use `assembleDebug` only when an actual APK is needed.

---

## Test Commands

```bash
# Run all JVM unit tests
./gradlew test

# Run a single unit test class
./gradlew test --tests "pl.zarajczyk.familyrulesandroid.ExampleUnitTest"

# Run a single test method
./gradlew test --tests "pl.zarajczyk.familyrulesandroid.ExampleUnitTest.addition_isCorrect"

# Run instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest

# Run a single instrumented test class
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=pl.zarajczyk.familyrulesandroid.ExampleInstrumentedTest
```

**Test libraries available:** JUnit 4, AndroidX Test / JUnit4 runner, Espresso, Compose `ui-test-junit4`.  
**Note:** Current test coverage is minimal (only scaffold tests). When adding new business logic, add JVM unit tests under `app/src/test/`.

---

## Architecture

```
Activities (UI, Compose)
    └── binds to → FamilyRulesCoreService (bound foreground Service)
                        ├── FamilyRulesClient (Retrofit + OkHttp + Moshi)
                        ├── AppDatabase (Room — icon/name cache)
                        ├── DeviceStateManager (StateFlow reactive state)
                        └── Processors: ScreenTimeCalculator, PackageUsageCalculator,
                            SystemEventLogger  (all implement SystemEventProcessor)
```

- **No ViewModel, no Hilt/Dagger/Koin.** Dependencies are wired manually via constructor injection and `companion object { fun install(...) }` factory methods.
- **Singletons** use Kotlin `object` (`Logger`, `ServiceKeepAliveAlarm`) or double-checked locking (`AppDatabase`).
- **Reactive state:** `DeviceStateManager` exposes `StateFlow<ActualDeviceState>`. UI collects it with `collectAsState()` inside Composables.
- **Polling loops** use `while (isActive) { ... delay(...) }` inside coroutine scopes.
- Keep-alive strategy (critical for reliability): exact AlarmManager + WorkManager + BootReceiver + `START_STICKY`.

---

## Code Style

### General
- Kotlin official code style (`kotlin.code.style=official` in `gradle.properties`).
- 4-space indentation. Opening brace on the same line.
- Single blank line between top-level declarations.

### Naming
| Element | Convention | Example |
|---|---|---|
| Classes / objects / interfaces | `UpperCamelCase` | `FamilyRulesCoreService` |
| Functions / properties / variables | `lowerCamelCase` | `getTodayScreenTime()` |
| Constants in `companion object` | `UPPER_SNAKE_CASE` | `CHANNEL_ID`, `TAG` |
| Private log tag | `private const val TAG = "ClassName"` | ubiquitous |
| Packages | all lowercase | `pl.zarajczyk.familyrulesandroid.core` |
| `@Composable` functions | `UpperCamelCase` noun/noun-phrase | `MainScreen`, `ProtectionCard` |
| `@Preview` functions | `UpperCamelCase` + `Preview` suffix | `UsageStatsDisplayPreview` |

### Imports
- Wildcard imports are used for internal packages (e.g., `import pl.zarajczyk.familyrulesandroid.core.*`).
- Specific imports for external libraries.
- No enforced ordering; follow IntelliJ/Android Studio's default auto-import order.

### Kotlin Idioms
- Prefer `apply {}` for object configuration: `Intent(...).apply { putExtra(...) }`.
- Prefer `when` expressions over `if/else if` chains for multi-branch logic.
- Use `data class` for DTOs and value-carrying types.
- Use `sealed class` for typed results (see `InitialSetupActivity.Result`).
- Mark shared mutable fields accessed from multiple coroutines with `@Volatile`.
- Use `@Suppress("DEPRECATION")` with an explanatory comment when unavoidable.

### Coroutines
- Scope: `CoroutineScope(Dispatchers.Default + SupervisorJob())` for long-lived service scopes.
- Use `SupervisorJob` so a child failure does not cancel siblings.
- IO-bound work: `withContext(Dispatchers.IO)`.
- Long-running loops: `while (isActive) { ... delay(...) }`.
- Compose: `LaunchedEffect` + `collectAsState` for reactive UI; `remember { mutableStateOf(...) }` for local state.

### Error Handling
- Catch `Exception` broadly at service/repository boundaries: `try { ... } catch (e: Exception) { Logger.e(TAG, "...", e) }`.
- Suspend functions return `null` on recoverable errors rather than propagating exceptions (e.g., `getGroupsUsageReport(): AppGroupsUsageReportResponse?`).
- Use `sealed class Result { data class Success(...); data class Error(...) }` for operations that must communicate failure details to the caller.
- Always log errors through the project's `Logger` utility (`utils/Logger.kt`), not directly via `Log`.

### Compose
- State hoisting: pass state + lambdas down to child composables; children do not read global state directly.
- Annotate composables needing experimental APIs with `@OptIn(ExperimentalMaterial3Api::class)`.
- Theme tokens come from `ui/theme/` (`FamilyRulesColors`, `Theme.kt`, `Type.kt`) — do not hardcode colors or text styles.

---

## Networking (Retrofit + Moshi)

- `FamilyRulesApiService` defines the Retrofit interface and all DTOs.
- `FamilyRulesClient` wraps Retrofit with an OkHttp interceptor for Basic Auth (`instanceId:token`).
- All API calls are `suspend` functions dispatched to `Dispatchers.IO`.
- JSON serialization: Moshi with `MoshiConverterFactory`.

---

## Database (Room)

- Single entity: `AppInfo` (app icon cache — Base64 PNG + app name).
- `OnConflictStrategy.REPLACE` everywhere.
- Access via `AppDb` singleton facade; never instantiate `AppDatabase` directly.

---

## Logging

Use `Logger` (`utils/Logger.kt`) for all log output:

```kotlin
Logger.d(TAG, "debug message")
Logger.i(TAG, "info message")
Logger.w(TAG, "warning message")
Logger.e(TAG, "error message", exception)
```

`Logger` writes to daily rotating files in `filesDir/logs/` (last 3 days kept) and also forwards to `android.util.Log`.

---

## Key Files Reference

| File | Role |
|---|---|
| `core/FamilyRulesCoreService.kt` | Heart of the app — foreground service, orchestrates everything |
| `adapter/FamilyRulesApiService.kt` | Retrofit interface + all API DTOs |
| `adapter/FamilyRulesClient.kt` | HTTP client, auth interceptor |
| `core/DeviceStateManager.kt` | Reactive `StateFlow` state container |
| `core/SettingsManager.kt` | Shared preferences wrapper |
| `database/AppDb.kt` | Room singleton facade |
| `utils/Logger.kt` | Project-wide logger |
| `gradle/libs.versions.toml` | Version catalog — add/update dependencies here |
| `app/build.gradle.kts` | Module build config |
| `release.sh` | Manual release — bumps version, tags, pushes |
