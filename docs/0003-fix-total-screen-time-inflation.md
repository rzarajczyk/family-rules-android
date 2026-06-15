## Problem

Total screen time on Zosia's tablet (Samsung SM-X610, Android 16) could report hours of use while per-app usage showed `00m 00s` and the display had been off for most of the morning.

Per-app totals were fixed in [0002](0002-fix-youtube-overnight-usage-inflation.md) by moving daily attribution to `PackageUsageStatsProvider` replay with `buildScreenOnIntervals`. Total screen time still used the incremental `ScreenTimeCalculator`, which suffered the same stale-session failure modes:

- Assumed the screen stayed on for an entire batch when usage events were missing
- Synthesized `SCREEN_INTERACTIVE` at batch start when the close event had been evicted from the OS buffer
- Did not apply the v0.96 stale-carry guards already used for per-app replay

On 2026-06-15 the tablet reported impossible totals while asleep:

```text
2026-06-15 00:04:31.739 [I] FamilyRulesClient: Uptime reported [screen time: 06h 48m 44s; top 3 apps: ], received device state: ACTIVE
2026-06-15 06:50:44.101 [I] FamilyRulesClient: Uptime reported [screen time: 03h 31m 23s; top 3 apps: com.sec.android.app.launcher (00m 00s)], received device state: ACTIVE
```

`06h 48m 44s` at four minutes past midnight is not physically possible for one calendar day. After a 03:31 shutdown and 03:44 reboot, `03h 31m 23s` matched midnight → shutdown — phantom screen-on time with no app attribution while the screen stayed off until 07:20.

`device state: ACTIVE` is parental-control policy only; it does not mean the display was on.

## What Was Done

1. **Removed `ScreenTimeCalculator`** from the incremental `PeriodicUsageEventsMonitor` pipeline.
2. **Added `PackageUsageStatsProvider.getTodayScreenTime()`**, computed by `computeTodayScreenTime(...)` — the sum of intervals from the same `buildScreenOnIntervals(...)` used for per-app attribution.
3. **Hardened `buildScreenOnIntervals(...)`** with stale-carry guards aligned to doc 0002:
   - Drop pre-midnight `SCREEN_INTERACTIVE` carry when there is no interactive event today and the last interactive was more than three hours before midnight
   - Do not count a midnight → `DEVICE_SHUTDOWN` interval unless `SCREEN_INTERACTIVE` occurred today first (discards phantom overnight totals)
   - Reset a stale midnight clip to the first `SCREEN_INTERACTIVE` timestamp today

Both metrics now share one replay query and one screen-on interval model.

## Why This Approach

Patching the incremental accumulator again would duplicate logic already proven in `PackageUsageStatsProvider`. Summing `buildScreenOnIntervals` keeps total screen time consistent with per-app totals and reuses the same regression tests and exported-log replay infrastructure.

## Tests Added

Extended `PackageUsageStatsProviderTest` with:

- `computeTodayScreenTime sums clipped screen-on intervals`
- `stale pre-midnight screen-on without interactive today returns zero screen time`
- `replay june 15 shutdown does not count phantom midnight to shutdown screen time` — reproduces the 2026-06-15 field pattern

## Verification

- `./gradlew app:test`
- `./gradlew compileDebugKotlin`
