## Problem

Per-app usage totals could become wildly inflated for apps such as Samsung Camera.

The failure mode was a real Android event sequence where an activity produced:

```text
ACTIVITY_RESUMED
ACTIVITY_STOPPED
```

without a matching `ACTIVITY_PAUSED`.

`PackageUsageStatsProvider` replayed only `ACTIVITY_RESUMED` and `ACTIVITY_PAUSED`, so the package stayed logically open and its usage kept accumulating until `now`. When that stale session crossed midnight, the next day's total appeared as if the app had been open since midnight.

## What Was Done

Updated `PackageUsageStatsProvider` so `ACTIVITY_STOPPED` is included in replay and treated as a fallback close signal.

- `readEvents(...)` now emits `ACTIVITY_STOPPED`
- `computeTodayPackageUsage(...)` now keeps `ACTIVITY_STOPPED` in its internal filter
- `ACTIVITY_STOPPED` closes a session only if that exact class is still active

This preserves the preferred `ACTIVITY_PAUSED` boundary while fixing real-world streams that omit `PAUSED` entirely.

## Why This Approach

April history showed that `ACTIVITY_STOPPED` had been intentionally deprioritized because it is often delayed compared with `ACTIVITY_PAUSED`.

The fix keeps that intent:

- `PAUSED` remains the primary close event
- delayed `STOPPED` does not double-close an already closed session
- same-package handoff behavior remains intact

This is the smallest change that fixes the Camera bug without regressing the handoff logic introduced in the later replay-based implementation.

## Test Infrastructure Added

Added reusable test-side infrastructure to replay raw `SYSTEM EVENTS` excerpts directly from exported logs.

- New helper: `UsageEventLogReplay`
- Parses log lines into `UsageEventTuple`
- Keeps only lifecycle events relevant to package usage replay

This allows future regressions to be captured using realistic Android event sequences instead of only synthetic tuples.

## Tests Added

Extended `PackageUsageStatsProviderTest` with coverage for:

- `RESUME -> STOPPED` for the same class
- `PAUSE -> delayed STOPPED` without double-close
- cross-midnight clipping with `STOPPED`
- same-package handoff with `STOPPED` noise
- a real camera regression replay built from exported system-event lines

## Verification

- `./gradlew app:test`
- `./gradlew compileDebugKotlin`

Lint still reports existing unrelated manifest issues and was not changed as part of this fix.
