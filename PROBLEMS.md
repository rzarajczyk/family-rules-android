# Known Problems — Why YouTube Can Still Be Used While It Should Be Blocked

This document was re-verified against the current codebase.

Scope: explain real code paths that can lead to this symptom:

> the child is sometimes able to watch YouTube even though YouTube should already be blocked.

Only problems that are confirmed in code, or clearly marked as a hypothesis, are included.
Nothing in this file is resolved.

---

## Problem 1 — Foreground monitoring only reacts to app changes

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/ForegroundAppMonitor.kt`

**Status:** Open
**Confidence:** Confirmed

`ForegroundAppMonitor.checkForegroundApp()` only does anything when the foreground package
changes:

```kotlin
if (currentApp != null && currentApp != lastForegroundApp) {
    lastForegroundApp = currentApp
    if (currentApp in packagesToBlock) {
        showBlockingOverlay(currentApp)
    } else {
        hideBlockingOverlay()
    }
}
```

This creates a real failure mode:

- a blocked app is already the remembered `lastForegroundApp`
- monitoring is stopped and later started again
- the child opens the same app again
- no "app changed" event is observed from this monitor's point of view
- the overlay is not shown

`stopMonitoring()` also does not reset `lastForegroundApp`, which makes this easier to hit
across separate blocking sessions.

**Why this matters to YouTube blocking:**
If YouTube is the blocked app and it matches the stale `lastForegroundApp`, the blocking
overlay can fail to appear even though blocking is logically active.

**Needed change:**
- reset `lastForegroundApp` when monitoring stops
- while a blocked app remains in foreground, re-assert the overlay periodically instead of
  only on package changes

---

## Problem 2 — Network errors fail open to `ACTIVE`

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/adapter/FamilyRulesClient.kt`
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/PeriodicReportSender.kt`

**Status:** Open
**Confidence:** Confirmed

On any exception during `reportUptime()`, the client returns `ActualDeviceState.ACTIVE`:

```kotlin
} catch (e: Exception) {
    ActualDeviceState.ACTIVE
}
```

`PeriodicReportSender` then treats that as a normal server decision and may call
`appBlocker.unblock()` when coming from a blocking state.

**Why this matters to YouTube blocking:**
Any transient network failure can turn blocking off locally, even if the server still expects
YouTube to be blocked.

This is especially dangerous because the failure is not rare or adversarial-only. It can be
triggered by ordinary connectivity issues, and a child could also force it by toggling network
connectivity.

**Needed change:**
- do not map network failure to `ACTIVE`
- treat report failure as "state unknown, keep current local blocking state"

---

## Problem 3 — If fetching blocked apps fails once, blocking may never arm

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/PeriodicReportSender.kt`
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/adapter/FamilyRulesClient.kt`

**Status:** Open
**Confidence:** Confirmed

When entering a blocking state, `PeriodicReportSender` fetches the blocked packages and then
calls `appBlocker.block(appList)`.

But `FamilyRulesClient.getBlockedApps()` returns `emptyList()` on error:

```kotlin
} catch (e: Exception) {
    emptyList()
}
```

That means this sequence is possible:

1. server says device should be in `BLOCK_RESTRICTED_APPS`
2. `getBlockedApps()` fails
3. app calls `appBlocker.block(emptyList())`
4. no packages are actually blocked
5. `currentDeviceState` is still updated to the blocking state
6. later reports with the same state are ignored because no state transition happened

At that point the app is in a "blocking" state on paper, but YouTube is still usable until the
server state changes away from blocking and back again.

The same issue exists for `BLOCK_RESTRICTED_APPS_WITH_TIMEOUT`.

**Why this matters to YouTube blocking:**
One failed blocked-app fetch at exactly the wrong moment can leave YouTube unblocked for an
extended period.

**Needed change:**
- track whether blocking is actually armed, not only which state was requested
- retry app-list fetch while in blocking mode until non-empty blocking is applied
- keep a cached last-known blocked list as fallback

---

## Problem 4 — Blocked app list is not refreshed while already in a blocking state

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/PeriodicReportSender.kt`

**Status:** Open
**Confidence:** Confirmed

Blocked apps are only fetched when `currentDeviceState != newState`.

So if the server keeps returning `BLOCK_RESTRICTED_APPS` but changes the contents of the blocked
group, the client does not re-fetch the list.

Real example:

1. device is already in `BLOCK_RESTRICTED_APPS`
2. server-side configuration is changed so YouTube is now part of the blocked group
3. server still returns the same device state
4. client sees "no state change" and skips `getBlockedApps()` entirely
5. YouTube stays usable until some later state transition happens

**Why this matters to YouTube blocking:**
This is a direct explanation for "sometimes YouTube is still watchable" after remote rule
changes, even without any crash or network outage.

**Needed change:**
- refresh blocked apps periodically while a blocking state is active, or
- include enough versioning/hash information in the server response to know that the blocked
  set changed even if the device state name did not

---

## Problem 5 — Keep-alive exact alarm is broken after service shutdown or first fire

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/FamilyRulesCoreService.kt`
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/ServiceKeepAliveAlarm.kt`
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/entrypoints/ServiceRestartReceiver.kt`

**Status:** Open
**Confidence:** Confirmed

There are two separate problems in the exact-alarm keep-alive path.

### 5A. `onDestroy()` cancels the keep-alive alarm

`FamilyRulesCoreService.onDestroy()` calls:

```kotlin
ServiceKeepAliveAlarm.cancelAlarm(this)
```

If the service is killed by the system or by vendor battery management, this removes the
fastest restart mechanism.

### 5B. The alarm is one-shot and the receiver does not re-arm it

`ServiceKeepAliveAlarm.scheduleAlarm()` uses `setExactAndAllowWhileIdle()`, which schedules a
single future alarm, not a repeating one.

The alarm is scheduled in `FamilyRulesCoreService.onCreate()`. When it later fires,
`ServiceRestartReceiver` only checks whether the service is already running. If it is, the
receiver does nothing and does not schedule the next alarm.

So after the first successful alarm delivery, the exact-alarm chain can silently stop.

**Why this matters to YouTube blocking:**
If the core service dies later, foreground monitoring and periodic reporting stop. That can
leave YouTube usable until the slower recovery paths bring the service back.

**Needed change:**
- do not cancel the alarm in normal `onDestroy()`
- always re-arm the next exact alarm in `ServiceRestartReceiver.onReceive()` before checking
  service state

---

## Problem 6 — Overlay service is `START_NOT_STICKY`

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/AppBlockingOverlayService.kt`

**Status:** Open
**Confidence:** Confirmed

`AppBlockingOverlayService.onStartCommand()` returns `START_NOT_STICKY`.

If that service is killed after the overlay is shown, Android is not asked to recreate it.
Because foreground monitoring only reacts to app changes today, the overlay may also fail to be
shown again promptly.

**Why this matters to YouTube blocking:**
YouTube can become visible and usable again if the overlay service dies while the blocked app is
still foregrounded.

**Needed change:**
- make the overlay service more resilient, and
- pair that with Problem 1's fix so the overlay is re-asserted while a blocked app stays in
  foreground

---

## Problem 7 — No persisted fallback for the blocked app list after process death

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/PeriodicReportSender.kt`
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/SettingsManager.kt`

**Status:** Open
**Confidence:** Confirmed

There is no persisted `cachedBlockedApps` mechanism.

After process death or service restart, the app starts with no remembered blocked package list.
If the first `getBlockedApps()` attempt fails at that point, there is no fallback list that could
still be used to block YouTube.

This is related to Problem 3, but it is distinct:

- Problem 3 is about failing to arm blocking after one bad fetch
- this problem is about having no last-known-good blocked list to fall back to after restart

**Why this matters to YouTube blocking:**
Service restarts are exactly the moments when the app is most fragile. Without a persisted cached
list, a restart plus one fetch failure can leave YouTube unblocked.

**Needed change:**
- persist the last known blocked package list
- restore it on startup and use it as fallback when live fetch fails

---

## Problem 8 — Battery-optimization setup status is currently inverted

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/PermissionsSetupActivity.kt`

**Status:** Open
**Confidence:** Confirmed

The battery-optimization helper is misnamed and used backwards.

`isBatteryOptimizationEnabled()` currently returns:

```kotlin
powerManager.isIgnoringBatteryOptimizations(context.packageName)
```

But the callers negate that value and store it as `batteryOptimizationDisabled`.

That means the setup screen can report the battery requirement as satisfied when the app is
actually still subject to battery optimization, and vice versa.

**Why this matters to YouTube blocking:**
This increases the chance that the core service will be killed in the background while the setup
UI falsely suggests the protection is configured correctly.

**Needed change:**
- fix the boolean naming and logic so the setup screen reflects the real battery-optimization
  state

---

## Problem 9 — Samsung-specific battery management is still an unverified but credible risk

**Files:**
- `app/src/main/java/pl/zarajczyk/familyrulesandroid/PermissionsSetupActivity.kt`

**Status:** Open
**Confidence:** Hypothesis / mitigation gap

The codebase contains no Samsung-specific guidance or deep link for OneUI's additional battery
management screens.

That does **not** prove that Samsung battery management is the root cause of the YouTube issue.
The code alone cannot establish that. But it does show a missing mitigation for a device family
that is known to kill background work aggressively.

This item should be treated as a likely contributing factor, not as a confirmed root cause.

**Why this matters to YouTube blocking:**
If Samsung kills the service more aggressively than stock Android, Problems 5, 7, and 8 become
more visible in real-world use.

**Needed change:**
- add Samsung-specific setup guidance, but do not confuse that with a proven fix for the core
  logic bugs above
