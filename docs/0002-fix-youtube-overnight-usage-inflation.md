## Problem

Per-app usage for YouTube on Zosia's tablet (Samsung SM-X610, Android 16) showed **10h 34m** while screen time and Family Link both reported roughly **2 hours** of actual use.

`PackageUsageStatsProvider` replays `UsageEvents` and treats a package as foreground from `ACTIVITY_RESUMED` until a matching `ACTIVITY_PAUSED` or `ACTIVITY_STOPPED`. When that close event is missing, the session stays open and accumulates until `now`. After crossing midnight, today's total becomes "open since midnight" even though the device was off or the app was only playing audio in the background.

On 2026-06-10 the tablet booted around 10:33. The first uptime report already carried the inflated number:

```text
2026-06-10 10:34:09.603 [I] FamilyRulesClient: Uptime reported [screen time: 00m 30s; top 3 apps: com.google.android.youtube (10h 34m 08s), com.sec.android.app.launcher (00m 11s)], received device state: BLOCK_RESTRICTED_APPS_WITH_TIMEOUT
```

`10h 34m 08s` at `10:34:09` is essentially elapsed time since local midnight (`00:00:08` → `10:34:09`), not real foreground usage. Screen time at the same moment was only 30 seconds (time since the Family Rules service started on boot).

Later reports kept YouTube frozen at `10h 34m 19s` while screen time grew normally:

```text
2026-06-10 15:28:12.406 [I] FamilyRulesClient: Uptime reported [screen time: 01h 51m 07s; top 3 apps: com.google.android.youtube (10h 34m 19s), com.sec.android.app.launcher (01h 47m 43s), com.whatsapp (02m 12s)], received device state: BLOCK_RESTRICTED_APPS_WITH_TIMEOUT
```

Exported system events for the day show almost no YouTube lifecycle activity before boot — the first `ACTIVITY_RESUMED` is at 10:33:51, yet the reported total already includes the whole morning:

```text
2026-06-10 10:33:37.328  SCREEN_INTERACTIVE (15)  android/
2026-06-10 10:33:51.701  ACTIVITY_RESUMED (1)  com.google.android.youtube/ com.google.android.apps.youtube.app.watchwhile.MainActivity
2026-06-10 10:34:14.386  ACTIVITY_PAUSED (2)  com.google.android.youtube/ com.google.android.apps.youtube.app.watchwhile.MainActivity
```

The stale `ACTIVITY_RESUMED` from the previous evening (within the 24h lookback window) never received `PAUSED`/`STOPPED` when the tablet slept. After midnight the replay clipped that phantom session to `[startOfDay, first close]`, producing ~10.5 hours of bogus YouTube time.

Firestore stored the client value verbatim (`screenTime` 7924s ≈ 2h 12m, `com.google.android.youtube` 38059s ≈ 10h 34m) — the inflation is entirely on-device.

## What Was Done

Close all in-flight sessions on `DEVICE_STARTUP` (event type 27, API 28+):

- `readEvents(...)` now emits `DEVICE_STARTUP`
- `computeTodayPackageUsage(...)` replicates startup into every known package stream (same pattern as `DEVICE_SHUTDOWN`)
- On `DEVICE_STARTUP`, clear `active` and `openSince` **without** accumulating time for the orphaned pre-boot session

`DEVICE_SHUTDOWN` still closes sessions and counts time up to shutdown. A clean reboot sequence (`SHUTDOWN` → `STARTUP`) therefore preserves usage up to power-off; only the stale tail that survives into the next boot is discarded.

## Why This Approach

`ACTIVITY_STOPPED` (fix 0000) handles missing `PAUSED` while the process is still alive. It does not help when the OS loses lifecycle events across a full reboot.

Counting orphaned sessions up to the `DEVICE_STARTUP` timestamp would still attribute overnight sleep (midnight → boot) as foreground time because cross-midnight clipping starts at `startOfDay`.

Discarding on startup matches the physical reality: after reboot no activity is still foreground until a fresh `RESUME`.

## Tests Added

Extended `PackageUsageStatsProviderTest` with:

- `DEVICE_STARTUP discards stale overnight session without counting sleep as today usage` — reproduces the 2026-06-10 YouTube inflation pattern
- `shutdown before startup still counts active usage up to shutdown` — guards the clean-reboot path

## Follow-up (v0.94 field test)

v0.94 shipped the `DEVICE_STARTUP` fix only. On Zosia's Samsung tablet (SM-X610, Android 16) **nothing changed** — the inflation persisted.

The exported system events confirm why: the morning wake at 10:33 emits `SCREEN_INTERACTIVE` and `EVENT_28`, but **no `DEVICE_STARTUP (27)`**. Samsung omits startup on wake-from-sleep; the v0.94 code path never ran.

The same log **does** contain `SCREEN_NON_INTERACTIVE` when the display turns off:

```text
2026-06-10 10:36:30.678  SCREEN_NON_INTERACTIVE (16)  android/
2026-06-10 13:55:49.160  SCREEN_NON_INTERACTIVE (16)  android/
```

## Additional fix

Also close open sessions on `SCREEN_NON_INTERACTIVE`, using the same accumulate-and-close semantics as `DEVICE_SHUTDOWN`: foreground usage ends when the display turns off, even if the app never emitted `ACTIVITY_PAUSED`.

This covers the Samsung wake-from-sleep path: a stale evening `RESUME` is closed at the last screen-off before midnight, so it does not carry into today's total.

## Verification

- `./gradlew app:test`
- `./gradlew compileDebugKotlin`
