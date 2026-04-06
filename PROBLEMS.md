# Known Problems — YouTube Blocking Unreliable on Samsung Galaxy Tab S9 FE+

Analysis of why a blocked app (e.g. YouTube) can still be used despite screen time limits
being exceeded. Seven problems were identified. None have been resolved yet.

---

## Problem 1 — Stale `lastForegroundApp` Prevents Overlay From Showing

**File:** `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/ForegroundAppMonitor.kt`  
**Status:** Open

`checkForegroundApp()` previously only acted when the foreground app **changed**. Because
`lastForegroundApp` was never reset in `stopMonitoring()`, if YouTube was the last tracked
foreground app in a previous blocking session and the child opens YouTube again in the next
blocking session, `currentApp == lastForegroundApp` was `true` and the overlay was never
shown.

**Proposed fix:**
- `stopMonitoring()` should reset `lastForegroundApp = null`.
- `checkForegroundApp()` should re-send the `show` overlay command on **every poll tick**
  while a blocked app is in the foreground (not only on app change). This would also resolve
  Problem 6 entirely (see below).

---

## Problem 2 — Fail-Open on Network Errors Immediately Lifts All Blocks

**File:** `app/src/main/java/pl/zarajczyk/familyrulesandroid/adapter/FamilyRulesClient.kt`  
**Status:** Open

Any network failure (timeout, DNS error, WiFi blip) causes `reportUptime()` to
return `ActualDeviceState.ACTIVE`, immediately unblocking everything:

```kotlin
} catch (e: Exception) {
    ActualDeviceState.ACTIVE  // current behaviour
}
```

A child could exploit this by toggling airplane mode briefly, or it could trigger on any
ordinary WiFi hiccup.

**Proposed fix:**
- `reportUptime()` should return `ActualDeviceState?` — `null` on network error.
- `PeriodicReportSender.reportUptime()` should treat a `null` response as "keep the current
  state" and return early without calling `handleDeviceStateChange()`. Blocking should not
  be lifted on a transient network failure.

**Remaining gap:** On a fresh service start (e.g. after Samsung killed it), `currentDeviceState`
resets to `ACTIVE`. If the first report also fails due to network error, the service stays
in `ACTIVE` while the server considers blocking to be active. This gap is related to
Problem 4 (the server state should be re-applied as soon as the first successful report
comes back, even without a state transition).

---

## Problem 3 — Keep-Alive Alarm Chain Breaks After First Fire

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/FamilyRulesCoreService.kt`
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/entrypoints/ServiceRestartReceiver.kt`  

**Status:** Open (two separate sub-issues)

**Sub-issue A — `onDestroy()` cancels the alarm:**  
`onDestroy()` currently calls `ServiceKeepAliveAlarm.cancelAlarm(this)`. When the system
or Samsung's battery optimizer kills the service, `onDestroy()` fires and actively
destroys the 5-minute exact AlarmManager alarm — the fastest recovery mechanism. After
that, recovery depends solely on `START_STICKY` (suppressible by Samsung) and WorkManager
(30-minute interval).

**Sub-issue B — alarm is one-shot and never re-armed:**  
`scheduleAlarm()` uses `setExactAndAllowWhileIdle`, which is a **one-shot** alarm. It is
only scheduled in `FamilyRulesCoreService.onCreate()`. When the alarm fires,
`ServiceRestartReceiver` either restarts the service (which then calls `onCreate()` and
re-arms) or, if the service is already running, does nothing — **without re-arming the
alarm**. After that single tick the alarm chain is silently broken, and if the service is
later killed, recovery can take up to 30 minutes via WorkManager.

**Proposed fix:**
- Remove `ServiceKeepAliveAlarm.cancelAlarm(this)` from `onDestroy()`.
- `ServiceRestartReceiver.onReceive()` should **always** call `scheduleAlarm()` for the next
  5-minute interval before checking whether the service needs restarting. This keeps the
  alarm chain alive regardless of service state.

---

## Problem 4 — Blocking Not Re-Applied After Service Restart

**File:** `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/PeriodicReportSender.kt`  
**Status:** Open

After Samsung kills the service and it restarts, `currentDeviceState` resets to `ACTIVE`
and `blockingArmed` is `false`. On the first successful server report:

- If the server returns `BLOCK_RESTRICTED_APPS`, `handleDeviceStateChange` sees a genuine
  `ACTIVE → BLOCK_RESTRICTED_APPS` transition and calls `getBlockedApps()` + `appBlocker.block()`.
  This part works correctly.
- But if `getBlockedApps()` also fails at this point, the old code calls
  `appBlocker.block(emptyList())` — no packages blocked — and then records
  `currentDeviceState = BLOCK_RESTRICTED_APPS`. Subsequent server responses returning the
  same state are silently ignored because the state hasn't changed, so blocking is never
  re-attempted until the server flips back to `ACTIVE` and then back to blocking again.

**Proposed fix:**
- Add a `blockingArmed: Boolean` flag (starts `false`, set `true` only when
  `appBlocker.block()` is called with a non-empty list, reset to `false` on `unblock()`).
- `handleDeviceStateChange` should re-attempt arming on every report cycle where
  `!blockingArmed`, even when `currentDeviceState` already equals the server's blocking
  state. This means after a restart + failed `getBlockedApps()`, the next successful
  server report automatically re-fetches and re-applies the blocked apps list.
- Add `cachedBlockedApps`: every successful `getBlockedApps()` response should update the
  cache. If the call fails or returns empty, the cached list should be used as a fallback,
  making `blockingArmed = true` achievable even with a temporarily broken server response.

---

## Problem 5 — Samsung OneUI Battery Optimization Silently Kills the Service

**File:** `app/src/main/java/pl/zarajczyk/familyrulesandroid/PermissionsSetupActivity.kt`  
**Status:** Open

Samsung OneUI has its own battery management layer (Sleeping Apps / Deep Sleeping Apps)
that operates independently of Android's standard `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
The app has no Samsung-specific handling at all.

**Proposed fix:**
- Add a `SamsungBatteryCard` composable in `PermissionsSetupActivity` that is shown only
  on Samsung devices (`Build.MANUFACTURER == "samsung"`).
- The card should describe the issue and provide an "Open settings" button that deep-links
  directly into Samsung's "Background usage limits" screen
  (`com.samsung.android.lool / BatteryActivity`), falling back to the standard Android
  battery settings page if the Samsung activity is not present.
- The card is informational — it cannot be checked programmatically, so it should not block
  the "Complete Setup" button.

---

## Problem 6 — `AppBlockingOverlayService` Is `START_NOT_STICKY`

**File:** `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/AppBlockingOverlayService.kt`  
**Status:** Open

`onStartCommand` currently returns `START_NOT_STICKY`, so the service is not restarted by
the system after being killed under memory pressure.

**Proposed fix:**
- `onStartCommand` should return `START_STICKY` so the service is restarted by the system
  after being killed under memory pressure.
- The root cause (overlay not re-shown after service death) would also be fully covered by
  the Problem 1 fix: `ForegroundAppMonitor` re-sending the `show` command on every poll
  tick while a blocked app is in the foreground would restore the overlay within 1 second
  regardless of why it disappeared.

---

## Problem 7 — Silent Failure When `getBlockedApps()` Returns Empty on Fresh Start

**File:** `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/PeriodicReportSender.kt`  
**Status:** Open

`cachedBlockedApps` is not persisted across process deaths. On a fresh service start after
process death, the cache is empty, so if `getBlockedApps()` also fails on the first cycle,
there is no fallback list available and blocking cannot be armed.

**Proposed fix:**
- Persist `cachedBlockedApps` to `SharedPreferences` (key `cachedBlockedApps`) via
  `SettingsManager` on every successful `getBlockedApps()` response.
- On service start, initialise `cachedBlockedApps` by loading from `SharedPreferences`,
  so the list is available immediately — even on the very first cycle after a process death
  or an app data migration — as long as the app has successfully fetched the list at least
  once before.
- The only remaining edge case (first-ever install before any successful fetch) is a
  temporary gap covered by the `blockingArmed` retry logic from Problem 4's fix.
