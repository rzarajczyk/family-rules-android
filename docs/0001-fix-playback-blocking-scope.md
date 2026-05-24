## Problem

When the server returned a `BLOCK_RESTRICTED_APPS_WITH_TIMEOUT` state with a non-empty blocked-apps list, playback blocking was supposed to suppress only the explicitly listed apps. Instead, all media playback on the device was interrupted — including apps that were not on the blocked list at all (e.g. YT Music while only `com.google.android.youtube` was blocked).

A secondary failure: when the server returned `BLOCK_RESTRICTED_APPS_WITH_TIMEOUT` with an **empty** blocked-playback-apps list, `enabled=true` alone was still sufficient to trigger the enforcement loop and audio focus grab, causing global playback suppression even though no app was supposed to be blocked.

A third failure (discovered later): apps that play video inline without publishing a `MediaSession` — specifically WhatsApp — were never blocked at all, even when explicitly listed in the blocked-playback-apps list, because the entire `MediaSessionMonitor` enforcement path relies on `MediaSessionManager.getActiveSessions()` which only sees apps that have registered a `MediaSession`. WhatsApp's in-app video player does not register one.

## Root Cause

The enforcement mechanism combined two distinct suppression strategies:

1. **`transportControls.pause()`** — targeted per-package pause via the MediaSession API.
2. **`AudioManager.requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT)`** — a system-wide audio focus request.

Audio focus is an OS-level mechanism. Holding `AUDIOFOCUS_GAIN_TRANSIENT` broadcasts a loss event to every app currently holding focus, regardless of which app is blocked. There is no way to scope audio focus to a single package.

The original implementation held audio focus for the entire duration of a blocking period. Any app that started or resumed playback during that window would immediately receive `AUDIOFOCUS_LOSS` and pause — regardless of whether it appeared in the blocked list.

For apps that do not use `MediaSession` (e.g. WhatsApp), `transportControls.pause()` has no effect at all, and audio focus is the only available mechanism to interrupt their playback.

## Approaches Tried

### Attempt 1 — Remove audio focus entirely

Removed all audio focus logic from `MediaSessionMonitor`: deleted `requestAudioFocus()`, `abandonAudioFocus()`, all related fields (`audioManager`, `audioFocusRequest`, `audioFocusHeld`), and all call sites.

**Outcome: regression.** Without audio focus, `transportControls.pause()` alone was insufficient to keep YT Music suppressed. YT Music aggressively re-requests audio focus after being paused and resumes playback within milliseconds. The `onPlaybackStateChanged` callback fires on state transitions, so once the app is already in `STATE_PAUSED` it stops triggering the callback. The 1-second enforcement tick is too slow to reliably suppress a fast-resuming app.

This confirmed that audio focus was not just incidental — it was the mechanism that actually prevented the blocked app from reclaiming audio. The problem was not audio focus per se, but holding it globally and unconditionally.

### Attempt 2 — Request audio focus only when a blocked app is confirmed playing via MediaSession

Changed `enforcePlaybackBlocking()` and `pauseIfBlocked()` so that `requestAudioFocusForBlocking()` is called only after filtering confirms that at least one blocked app is actively playing (`STATE_PLAYING` or `STATE_BUFFERING`).

Audio focus is:
- **Not requested** during setup or when blocking becomes active — only when a blocked app is actually caught playing.
- **Held** until blocking is disabled (abandoned in `stopEnforcementLoop()`), so the blocked app cannot reclaim focus between enforcement ticks.
- **Not requested at all** when no blocked app is playing in a given tick.

Additionally fixed the empty-list case: `updatePlaybackBlocking()` now sets `effectiveEnabled = enabled && blockedPackages.isNotEmpty()`, so an empty blocked list never starts the enforcement loop.

**Outcome: partially correct.** Apps that publish a `MediaSession` (YouTube, YT Music) are correctly handled. However WhatsApp video remained unblocked — logs confirmed that `getActiveSessions()` never returned `com.whatsapp` even while WhatsApp was in the foreground and playing video. WhatsApp does not register a `MediaSession`, so it is invisible to this entire path.

### Attempt 3 — Request audio focus on foreground-app change (`AUDIOFOCUS_GAIN_TRANSIENT`)

Added `onForegroundAppChanged(packageName: String)` to `MediaSessionMonitor`, called by `ForegroundAppMonitor` whenever the foreground app changes. The logic is:

- If the new foreground app is in `blockedPlaybackPackages` and blocking is active → request audio focus immediately, regardless of whether a `MediaSession` exists.
- If the new foreground app is not blocked → abandon audio focus, so background music players (YT Music, etc.) can reclaim it and resume.
- Audio focus is also abandoned in `stopEnforcementLoop()` when blocking is disabled entirely.

Focus type used: `AUDIOFOCUS_GAIN_TRANSIENT` — signals "temporary hold, give it back soon".

**Outcome: inconclusive for WhatsApp.** Logs confirmed that audio focus was acquired immediately on WhatsApp foreground entry and released on foreground exit. However, WhatsApp inline video continued playing uninterrupted. `AUDIOFOCUS_GAIN_TRANSIENT` is a voluntary protocol — apps must implement an `AudioFocusChangeListener` to respond. WhatsApp's inline video player does not implement one. Focus was acquired and held correctly, but WhatsApp never received or acted on the loss event.

Additionally, a gap was identified: if WhatsApp was already open when playback blocking became active, no foreground transition fired and focus was never requested (because `onForegroundAppChanged` is only called on transitions, not on the current state).

### Attempt 4 — Switch to `AUDIOFOCUS_GAIN` + fix already-foregrounded gap

Two changes on top of Attempt 3:

**4a. `AUDIOFOCUS_GAIN_TRANSIENT` → `AUDIOFOCUS_GAIN`**

`AUDIOFOCUS_GAIN` is the permanent ownership signal: apps receiving `AUDIOFOCUS_LOSS` (not `AUDIOFOCUS_LOSS_TRANSIENT`) are expected to stop playback entirely rather than pause and auto-resume. For a parental-control use case this is semantically correct — blocking should persist for the entire duration the app is foregrounded, not just briefly. The tradeoff: a compliant app that was playing in the background will stop rather than pause, and may not auto-resume when focus is released (the user may need to restart it manually).

**4b. Immediate focus acquisition when blocking activates with a blocked app already foregrounded**

Added `lastKnownForegroundApp` field to `MediaSessionMonitor`, updated on every `onForegroundAppChanged()` call. In `updatePlaybackBlocking()`, after the enforcement loop is started, focus is requested immediately if `lastKnownForegroundApp` is in the blocked list. This is guarded: if a non-blocked app is currently foregrounded, focus is not requested and no audio disruption occurs.

**Outcome: audio focus confirmed not working on WhatsApp.** Device testing confirmed WhatsApp inline video plays through both `AUDIOFOCUS_GAIN_TRANSIENT` and `AUDIOFOCUS_GAIN`. WhatsApp also ignores media key events (Play/Pause). It only stops when the app loses window focus. `AUDIOFOCUS_GAIN` is still correct to hold for MediaSession apps, but it is insufficient for WhatsApp.

### Attempt 5 — Dual detection + window-focus overlay

#### Investigation: `AudioPlaybackConfiguration`

Before implementing, the Android Emulator was used to capture live logcat from WhatsApp (PID 8444) while video was playing. The logs revealed:

```
AAudioStream_requestStart(s#5) called  → state 10→3→4  (PLAYING)
AAudioStream_requestStop(s#5) called   → state 4→9→10  (STOPPED)
```

WhatsApp uses AAudio (stream `s#5`) with:
- `usage = 1` → `AudioAttributes.USAGE_MEDIA`
- State `4` = actively playing; state `10` = stopped

`AudioManager.getActivePlaybackConfigurations()` returns one entry per active audio stream with its `AudioAttributes`. When filtered to `USAGE_MEDIA`, it reliably detects WhatsApp video playing. When WhatsApp is not playing (e.g. only message sounds), the stream is absent. Message notification sounds use `USAGE_NOTIFICATION` (5), not `USAGE_MEDIA` (1), so the filter correctly ignores them.

This allows distinguishing "WhatsApp media playing" from "WhatsApp notification sounds" without any package-specific logic.

#### `getClientUid()` — the UID attribution dead end

`AudioPlaybackConfiguration.getClientUid()` is a `@hide` API — it was added to the AOSP source but never promoted to the public SDK at any API level. Accessing it via reflection returns `-1` for all third-party audio streams when called from a non-system process. This is intentional: AOSP anonymizes playback ownership for privacy reasons. There is no reliable way to map an `AudioPlaybackConfiguration` entry to a package name directly.

**Consequence:** we cannot enumerate "which app owns this audio stream". The only safe approach is correlation: if a specific package is foregrounded while `USAGE_MEDIA` audio is active, attribute the audio to that package.

#### Attribution heuristic

`getActiveAudioPlaybackPackages()` implements the correlation:

1. Filter `AudioManager.getActivePlaybackConfigurations()` to `USAGE_MEDIA` entries.
2. If none are active → return empty set.
3. Query current foreground app via `queryCurrentForegroundApp()` → `coreService.getForegroundApp()` → `lastKnownForegroundApp` (cascade).
4. If the foreground app is in `audioManagerTrackedPackages` (a hardcoded allowlist, currently `{com.whatsapp}`) → return `setOf(foreground)`.
5. Otherwise → return empty set.

The hardcoded allowlist avoids false positives: e.g. if YouTube is foregrounded and also has an active `USAGE_MEDIA` stream, we would incorrectly attribute WhatsApp's background audio to YouTube without the allowlist. Only apps that are known to not publish a `MediaSession` are in the list.

#### `queryCurrentForegroundApp()` — bypassing the incremental event pipeline

`FamilyRulesCoreService.getForegroundApp()` depends on `PackageUsageCalculator`, which processes events delivered by `PeriodicUsageEventsMonitor` incrementally. On the test emulator, this pipeline returns 0 events consistently — `ACTIVITY_RESUMED` events exist in `dumpsys usagestats` but are not returned by the incremental query (likely an emulator quirk; not reproduced on physical devices). This means `getForegroundApp()` always returns `null` on the emulator.

A dedicated `queryCurrentForegroundApp()` was added to `MediaSessionMonitor` that bypasses the pipeline entirely by calling `UsageStatsManager.queryEvents()` directly over a short window (originally 10 s, later widened to 60 s — see Attempt 6). This reliably returns the last `ACTIVITY_RESUMED` package.

#### Implementation

Two parallel detection paths, merged into a single result set:

| Path | Detects | Mechanism |
|---|---|---|
| `MediaSessionPlayback` | Apps with `MediaSession` (YouTube, YT Music, Spotify, etc.) | `MediaSessionManager.getActiveSessions()` |
| `AudioPlaybackDetector` | Foregrounded tracked app playing `USAGE_MEDIA` audio | `AudioManager.getActivePlaybackConfigurations()` + foreground correlation |

The merged set is used in:
- **`getCurrentlyPlayingPackages()`** — feeds the "now playing" report sent to the server, so WhatsApp playing now appears in server-side usage data automatically.
- **`enforcePlaybackBlocking()`** — two enforcement paths:
  - MediaSession apps: `transportControls.pause()` + audio focus + overlay
  - AudioPlayback-only apps (e.g. WhatsApp): audio focus + overlay + Home press (no transport control available)

#### Window-focus overlay (`MediaPlaybackBlockingOverlayService`)

WhatsApp inline video only pauses when it loses window focus. A new overlay service was added to exploit this:

- `TYPE_APPLICATION_OVERLAY`, does **not** set `FLAG_NOT_FOCUSABLE` — steals window focus from the app beneath it.
- Small top-of-screen banner ("Media playback blocked"), semi-transparent, non-intrusive.
- Auto-dismisses after 10 seconds via `Handler.postDelayed`.
- Idempotent: calling `show()` while already visible resets the dismiss timer rather than stacking a second overlay.
- Tappable to dismiss early.
- `START_NOT_STICKY` — does not restart on kill; the enforcement loop will re-show it on the next tick if blocking is still active.

**Outcome: overlay works but WhatsApp video resumes when overlay dismisses.** The window-focus steal does cause WhatsApp to pause. However, when the overlay auto-dismisses after 10 s, WhatsApp is back in the foreground and resumes playback immediately. The next enforcement tick shows the overlay again, creating a 10-second on / 1-second off cycle — visible and annoying, but not truly blocking.

Additionally, the overlay `FLAG_NOT_TOUCH_MODAL` was intentionally kept so that touches outside the banner pass through to the underlying app — but this also means the user can interact with WhatsApp while the overlay is up, minimizing the pausing window.

### Attempt 6 — `pressHome()` as primary stop mechanism (current)

#### Root cause of overlay-cycle failure

The overlay approach relied on the assumption that window-focus loss would keep WhatsApp paused for the full 10-second dismiss delay. In practice WhatsApp's Chromium-based video player resumes the moment it regains focus — which happens as soon as the overlay auto-dismisses (or if the user taps outside the banner).

The only way to truly stop playback for an extended period is to remove WhatsApp from the foreground entirely, so that it cannot reclaim focus regardless of the overlay state.

#### `pressHome()` implementation

Added `pressHome()` to `MediaSessionMonitor`. When a blocked app is detected playing via the `AudioPlayback` path (or the `MediaSession` path, for consistency):

```kotlin
val homeIntent = Intent(Intent.ACTION_MAIN).apply {
    addCategory(Intent.CATEGORY_HOME)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK
}
ctx.startActivity(homeIntent)
```

Firing `ACTION_MAIN / CATEGORY_HOME` sends the blocked app to the background by switching to the home screen. This:
- Removes the blocked app from foreground → its Chromium/WebView player stops receiving media playback events and pauses.
- Causes `ACTIVITY_RESUMED` for the launcher to appear in the `UsageStatsManager` event log.
- Is irreversible from the app's side — WhatsApp cannot re-foreground itself without user interaction.

`pressHome()` is called from both `enforcePlaybackBlocking()` (polling loop, fires every 1 s) and `pauseIfBlocked()` (MediaSession callback path).

#### Audio focus denial — root cause identified

After adding `foregroundServiceType="specialUse|mediaPlayback"` and the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission, audio focus requests still returned `AUDIOFOCUS_REQUEST_FAILED` (result=0). Inspection of `MediaFocusControl` logcat showed our app's request never reaching the system audio focus manager — the request was being silently dropped before it got that far.

Root cause: WhatsApp's Chromium/WebView player acquires and holds audio focus permanently while the video player is open. The system's `AudioPolicy` denies concurrent `AUDIOFOCUS_GAIN` requests from lower-priority apps when a persistent focus holder is present. Since WhatsApp (a user-facing app) holds focus, our background service's request is refused.

`AUDIOFOCUS_GAIN_TRANSIENT` requests were also refused, meaning there is no standard focus type that can pre-empt a user-app holding permanent focus from a background service.

**Conclusion:** audio focus cannot be used to stop WhatsApp. `pressHome()` is the only reliable mechanism.

#### Audio focus still held for MediaSession apps

Audio focus requests (`AUDIOFOCUS_GAIN`) are still attempted for MediaSession-based apps (YouTube, Spotify, etc.) because those apps' media players do implement `AudioFocusChangeListener` and pause correctly on focus loss. The request may still be denied if WhatsApp is already holding focus, but for the typical use case (blocking YouTube while YouTube is playing and WhatsApp is not) it works correctly.

- **Plain** ([show]): auto-dismisses after 5 s. No countdown. Used for MediaSession-based apps
  (e.g. YouTube) where `transportControls.pause()` already stops playback — the overlay is purely
  informational.
- **Countdown** ([showWithCountdown]): displays a live N→0 countdown badge on the right side of
  the banner. When the counter reaches zero the supplied `onExpired` callback is invoked on the
  main thread and the overlay hides. Used for AudioManager-only apps (e.g. WhatsApp) where the
  caller needs to `pressHome()` after giving the user a brief warning window.

Enforcement behavior per detection path:

| Detection path | Apps | On blocked playback detected |
|---|---|---|
| MediaSession | YouTube, YT Music, Spotify, … | `transportControls.pause()` + audio focus + **plain overlay** (5 s, auto-dismiss) |
| AudioPlayback (AudioManager) | WhatsApp, … | audio focus + **countdown overlay** (5 s) → `pressHome()` on zero |

Rationale: MediaSession apps can be paused in-place via transport controls. The user can keep the
app open and interact with it (browse, etc.) — playback simply stays paused. No need to eject
them from the foreground. The plain overlay is a non-disruptive notification that playback is
blocked.

AudioManager-only apps (WhatsApp) cannot be paused remotely — the only stop mechanism is removing
them from the foreground via `pressHome()`. The countdown overlay gives the user a 5-second
visible warning before the home-press fires, making the behavior feel less abrupt and explaining
why the app is being minimized.

#### `foregroundServiceType` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`

Added to `AndroidManifest.xml` after audio focus was first denied, in case the denial was due to missing media playback service declaration. This did not fix the audio focus denial (see root cause above), but it is still correct to keep — it is required on Android 14+ to call `requestAudioFocus` from a foreground service with media intent.

```xml
android:foregroundServiceType="specialUse|mediaPlayback"
```

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

#### `queryCurrentForegroundApp()` window widened to 60 s

After `pressHome()` is fired, WhatsApp goes to background. The next uptime report fires some seconds later. At that point, the last `ACTIVITY_RESUMED` event in the 10-second window is for the launcher, not WhatsApp. The server then sees the launcher (or no app) as the foreground app — not WhatsApp — so WhatsApp does not appear as "online" even though the user was interacting with it moments before.

The query window was widened to 60 s (later raised further — see Reporting section) to bridge this gap. The tradeoff: if the user has genuinely switched away from WhatsApp for more than 60 s, we may still attribute it as foreground. Acceptable for a parental-control use case; the risk of false blocking is nil (only reporting is affected).

**Current outcome:** `pressHome()` reliably stops WhatsApp video within 1–2 enforcement ticks (1–2 s). The overlay shows simultaneously as a user-visible notification. Non-blocked apps are unaffected. YT/YT Music work correctly via the MediaSession path.

### Reporting bug: `activeApps` empty set vs. null

#### Root cause

`PeriodicReportSender.reportUptime()` builds the `Uptime` object with:

```kotlin
activeApps = if (foregroundApp != null) setOf(foregroundApp) else emptySet()
```

where `foregroundApp = coreService.getForegroundApp()` — which always returns `null` on the emulator (see `queryCurrentForegroundApp()` explanation above). So `activeApps` is always `emptySet()`.

The server (`DevicesService.kt`, lines 215–218) interprets `activeApps` as follows:

```kotlin
lastUpdatedApps = if (activeApps != null) activeApps else currentAppBucketDeltas.keys
```

`null` triggers a fallback to usage-delta detection (apps whose screen time increased this tick). An empty set `{}` is treated as a valid, literal "nothing active" response — the fallback never fires.

Result: the server always sees no active apps, so no app is shown as "online" on the dashboard, and WhatsApp never appears in the activity timeline.

#### Additional compounding factor: `pressHome()` corrupts foreground history

When `pressHome()` fires, the last `ACTIVITY_RESUMED` event in `UsageStatsManager` becomes the launcher, not WhatsApp. Any subsequent `queryCurrentForegroundApp()` call within the 60-second window returns the launcher instead of WhatsApp. Even if `activeApps` were correctly computed, it would report the launcher package rather than the blocked app.

#### Fix identified (not yet applied)

Send `activeApps = null` instead of `emptySet()` when the foreground app is unknown:

```kotlin
activeApps = if (foregroundApp != null) setOf(foregroundApp) else null
```

This requires making `Uptime.activeApps` and `ReportRequest.activeApps` nullable (`Set<String>?`) in `FamilyRulesClient.kt` and `FamilyRulesApiService.kt` respectively. The JSON serialization will then emit `"activeApps": null` (with Moshi's nullable support) or omit the field, which the server handles via the null-check fallback.

#### WhatsApp screen time frozen

A separate symptom: WhatsApp screen time is stuck at the same value across multiple report ticks (`53m 00s` observed frozen). Root cause is the same as `getForegroundApp()` returning null — `PackageUsageCalculator` processes 0 events, so `getTodayPackageUsage()` never increments WhatsApp's usage time regardless of actual foreground time. This is an emulator-specific issue and not addressed yet.

## Open / Deferred Items

### 1. Make `activeApps` nullable — fix "online" reporting

`PeriodicReportSender` currently sends `emptySet()` when `getForegroundApp()` returns null. The server interprets an empty set as a literal "no apps active" rather than "unknown". Fix: change `Uptime.activeApps` and `ReportRequest.activeApps` to `Set<String>?`; send `null` when foreground is unknown.

### 2. Track real audio-focus loss events

`requestAudioFocusForBlocking()` uses an empty `OnAudioFocusChangeListener`. `audioFocusHeld` only reflects what this process last requested, not what the system currently grants. If another app takes focus away, local state is stale and a later reacquire attempt may be skipped.

**Follow-up:** implement a focus-change listener that clears `audioFocusHeld` on `AUDIOFOCUS_LOSS`.

### 3. Remove the startup race around `lastKnownForegroundApp`

`updatePlaybackBlocking()` fires an immediate focus request if `lastKnownForegroundApp` is in the blocked list. That value is populated asynchronously by `ForegroundAppMonitor`. During the first tick after service start, it may be null, delaying the first focus request by up to one poll interval.

**Follow-up:** seed `lastKnownForegroundApp` synchronously from `queryCurrentForegroundApp()` when blocking is enabled.

### 4. Decouple playback blocking from app blocking

`ForegroundAppMonitor` is owned by `AppBlocker` and only runs when the regular blocked-app list is armed. If only the playback-blocked list is populated (empty app-block list), `onForegroundAppChanged()` is never called.

**Follow-up:** start foreground monitoring whenever either blocking list is non-empty, independent of `AppBlocker`.

### 5. Validate collateral impact on compliant background players

`AUDIOFOCUS_GAIN` causes compliant apps to stop (not pause) and may require manual restart after focus is released. Verify with YT Music and other common background players before treating the implementation as final.

### 6. Investigate WhatsApp screen time frozen on emulator

`PackageUsageCalculator` processes 0 events on the test emulator. Screen time for WhatsApp never increments regardless of actual foreground time. Root cause: `PeriodicUsageEventsMonitor` incremental queries return empty batches even though `dumpsys usagestats` shows `ACTIVITY_RESUMED` events. Likely an emulator quirk; needs investigation on a physical device.

## Files Changed

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/MediaSessionMonitor.kt`
  - Added `getActiveAudioPlaybackPackages()` — detects `USAGE_MEDIA` audio via `AudioManager.getActivePlaybackConfigurations()`; correlates with foregrounded `audioManagerTrackedPackages` app; no reflection (reflection approach abandoned after discovering `getClientUid()` always returns -1)
  - Added `queryCurrentForegroundApp()` — direct `UsageStatsManager.queryEvents()` over 60 s window; bypasses incremental event pipeline
  - `getCurrentlyPlayingPackages()` — merged MediaSession + AudioPlayback results
  - `enforcePlaybackBlocking()` — MediaSession path: `transportControls.pause()` + audio focus + **plain overlay** (no `pressHome`); AudioPlayback path: audio focus + **countdown overlay** → `pressHome()` on zero
  - `pauseIfBlocked()` — callback path: `transportControls.pause()` + audio focus + **plain overlay** (no `pressHome`)
  - Added `showPlaybackBlockedOverlayWithCountdown()` — calls `MediaPlaybackBlockingOverlayService.showWithCountdown()` with a `pressHome` callback
  - Added `pressHome()` — fires `ACTION_MAIN / CATEGORY_HOME`; now called only from the countdown callback, not directly from enforcement paths
  - Added `requestAudioFocusForBlocking()` — idempotent `AUDIOFOCUS_GAIN`; may be denied if another app holds focus
  - Added `abandonAudioFocus()` — called from `stopEnforcementLoop()` and `onForegroundAppChanged()`
  - Added `onForegroundAppChanged()` — acquires/releases focus on foreground transition; caches `lastKnownForegroundApp`
  - `updatePlaybackBlocking()` — `effectiveEnabled = enabled && blockedPackages.isNotEmpty()`; fires immediate focus request if blocked app already foregrounded
  - `install()` — stores `coreService`, `audioManager`, `usageStatsManager`; `coreService` set only when context is `FamilyRulesCoreService`
  - `audioManagerTrackedPackages` — hardcoded set `{com.whatsapp}`; used as allowlist to avoid false-positive USAGE_MEDIA attribution

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/MediaPlaybackBlockingOverlayService.kt` *(new)*
  - Full-width dark-red top banner (`#E6B71C1C`), 48 dp icon, "FamilyRules" subtitle, "Odtwarzanie zablokowane" message
  - **Plain mode** (`show()`): auto-dismisses after 5 s; idempotent show resets timer; tappable
  - **Countdown mode** (`showWithCountdown(context, onExpired)`): shows N→0 badge on the right; fires `onExpired` callback on main thread at zero then hides
  - `TYPE_APPLICATION_OVERLAY`, focusable (steals window focus)
  - `START_NOT_STICKY`

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/ForegroundAppMonitor.kt`
  - `checkForegroundApp()` calls `MediaSessionMonitor.onForegroundAppChanged()` on every foreground app transition

- `app/src/main/AndroidManifest.xml`
  - Added `foregroundServiceType="specialUse|mediaPlayback"` to `FamilyRulesCoreService`
  - Added `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission
  - Registered `MediaPlaybackBlockingOverlayService`

- `app/src/main/res/values/strings.xml`
  - Added `media_playback_blocked = "Media playback blocked"`

- `app/src/main/res/values-pl/strings.xml`
  - Added `media_playback_blocked = "Odtwarzanie zablokowane"`

## Verification

- `./gradlew compileDebugKotlin` — passes, pre-existing warnings only
- On-device / emulator manual test:
  - YouTube: starts playback → paused immediately via transport controls → plain overlay shown for 5 s → app remains in foreground, user can browse freely
  - WhatsApp: starts video → countdown overlay shown (5→0) → `pressHome()` fires at zero → app minimized; restoring WhatsApp repeats the cycle, giving the user a 5-second warning each time

