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

A dedicated `queryCurrentForegroundApp()` was added to `MediaSessionMonitor` that bypasses the pipeline entirely by calling `UsageStatsManager.queryEvents()` directly over a 10 s window. This reliably returns the last `ACTIVITY_RESUMED` package.

#### Attribution heuristic (first version)

`getActiveAudioPlaybackPackages()` implements the correlation:

1. Filter `AudioManager.getActivePlaybackConfigurations()` to `USAGE_MEDIA` entries.
2. If none are active → return empty set.
3. Query current foreground app via `queryCurrentForegroundApp()` → `coreService.getForegroundApp()` → `lastKnownForegroundApp` (cascade).
4. If the foreground app is in `blockedPlaybackPackages` → return `setOf(foreground)`.
5. Otherwise → return empty set.

**Outcome: false positives.** The check `foreground in blockedPlaybackPackages` means any `USAGE_MEDIA` audio — including WhatsApp UI beeps when switching conversations — triggers enforcement as long as WhatsApp is foregrounded and is in the blocked list. The overlay and countdown fire on every conversation tap. Root cause: the `blockedPlaybackPackages` guard was intended to limit blast radius, but it still attributes any audio to WhatsApp just because it is foregrounded.

#### Window-focus overlay

WhatsApp inline video only pauses when it loses window focus. The overlay was added to exploit this — `TYPE_APPLICATION_OVERLAY` without `FLAG_NOT_FOCUSABLE` steals window focus from the app beneath it. Small top banner, auto-dismisses after 5 s, tappable.

**Outcome: overlay works but WhatsApp video resumes when overlay dismisses.** When the overlay auto-dismisses, WhatsApp regains window focus and resumes playback immediately. The next enforcement tick shows the overlay again — a 5-second on / 1-second off cycle. Not truly blocking.

### Attempt 6 — `pressHome()` as primary stop mechanism

#### Root cause of overlay-cycle failure

The only way to truly stop WhatsApp playback for an extended period is to remove it from the foreground entirely, so it cannot reclaim window focus.

#### `pressHome()` implementation

```kotlin
val homeIntent = Intent(Intent.ACTION_MAIN).apply {
    addCategory(Intent.CATEGORY_HOME)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK
}
ctx.startActivity(homeIntent)
```

Added to both `enforcePlaybackBlocking()` (polling loop) and `pauseIfBlocked()` (MediaSession callback). Called immediately when blocked playback is detected — no countdown.

**Outcome: works for WhatsApp but bad UX for YouTube.** YouTube also got `pressHome()` via the MediaSession callback path. YouTube has `transportControls.pause()` which stops playback in-place — minimizing it to home is unnecessarily disruptive. The user cannot browse YouTube while the blocking is active. Additionally: `pauseIfBlocked()` fires on the `onPlaybackStateChanged` callback, which is the reliable path; `enforcePlaybackBlocking()` only fires `pressHome()` during the brief window when the polling tick coincides with active playback — so it was unreliable when called from the loop but worked well from the callback.

### Attempt 7 — Split MediaSession vs. AudioPlayback enforcement UX

#### Observation

MediaSession apps (YouTube) can be paused in-place — `transportControls.pause()` is sufficient. The user can keep the app open and browse freely. `pressHome()` is unnecessary and disruptive.

AudioManager-only apps (WhatsApp) cannot be paused remotely. `pressHome()` is the only stop mechanism.

#### Change

- **MediaSession path** (`pauseIfBlocked` + `enforcePlaybackBlocking` MediaSession branch): `pause()` + audio focus + plain overlay (5 s auto-dismiss). No `pressHome()`.
- **AudioPlayback path** (`enforcePlaybackBlocking` AudioPlayback branch): audio focus + plain overlay + `pressHome()`. Immediate, no countdown.

**Outcome: correct for YouTube; WhatsApp still bad UX.** When WhatsApp is restored to foreground, video auto-resumes immediately, triggering instant `pressHome()` again. The app is unusable — every time the user opens it they are kicked back to home within 1 s. There is no window to use the app for non-video purposes.

### Attempt 8 — 5-second countdown before `pressHome()` for WhatsApp

#### Rationale

Give the user a visible warning and a chance to pause the video themselves before being sent home. If they pause during the countdown the overlay dismisses silently — no home press. If they do not pause, `pressHome()` fires at zero.

#### `audioManagerTrackedPackages` — hardcoded allowlist

The attribution check `foreground in blockedPlaybackPackages` was replaced with `foreground in audioManagerTrackedPackages` where `audioManagerTrackedPackages = setOf("com.whatsapp")`. This decouples audio detection from the server-driven blocked list: only apps explicitly known to use AudioManager without MediaSession are eligible for this path, preventing false attribution to other foregrounded blocked apps.

#### First countdown implementation — always shows "5"

The overlay countdown was implemented in `MediaPlaybackBlockingOverlayService`. The 1-second enforcement loop in `MediaSessionMonitor` calls `showWithCountdown()` on every tick. The first implementation of `showCountdownOverlay()` called `cancelAllPending()` unconditionally at the top, which tore down the existing overlay and restarted from 5 every second.

**Fix:** added `countdownActive` flag. `showCountdownOverlay()` returns immediately if `countdownActive == true`, so the running countdown is never restarted by the loop.

#### Overlay keeps appearing even when WhatsApp is not playing

After fixing the countdown restart, the overlay still appeared every time WhatsApp was opened — even with no video playing. Root cause identified via `dumpsys audio`:

```
AudioPlaybackConfiguration ... type:android.media.MediaPlayer u/pid:10133/1613
  attr: usage=USAGE_MEDIA content=CONTENT_TYPE_SONIFICATION
```

WhatsApp's UI sounds (beeps on conversation switch, tap feedback) declare `usage=USAGE_MEDIA` but `content=CONTENT_TYPE_SONIFICATION`. The original filter `usage == USAGE_MEDIA` matched these, so any UI interaction in WhatsApp while it was foregrounded triggered the countdown.

**Fix:** tightened the filter to exclude `CONTENT_TYPE_SONIFICATION`:

```kotlin
configs.filter {
    it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA &&
    it.audioAttributes.contentType != AudioAttributes.CONTENT_TYPE_SONIFICATION
}
```

Actual video playback uses `CONTENT_TYPE_MOVIE` or `CONTENT_TYPE_MUSIC` and still passes the filter.

#### `isStillPlaying` check per countdown tick

Each second during the countdown, `isStillPlaying()` is re-evaluated by calling `getActiveAudioPlaybackPackages()`. If it returns empty (user paused the video), the countdown cancels and the overlay dismisses silently without `pressHome()`.

**Outcome (current):** WhatsApp video detected only when genuinely playing (non-sonification `USAGE_MEDIA` audio). Countdown shows 5→0; user can cancel by pausing; `pressHome()` fires only if they do not pause. YouTube unaffected — paused in-place via transport controls, plain overlay, stays in foreground.

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
  - `getActiveAudioPlaybackPackages()` — detects `USAGE_MEDIA && contentType != SONIFICATION` audio; correlates with foregrounded `audioManagerTrackedPackages` app (`{com.whatsapp}`)
  - `queryCurrentForegroundApp()` — direct `UsageStatsManager.queryEvents()` over 10 s window; bypasses incremental event pipeline
  - `getCurrentlyPlayingPackages()` — merged MediaSession + AudioPlayback results
  - `enforcePlaybackBlocking()` — MediaSession path: `transportControls.pause()` + audio focus + plain overlay; AudioPlayback path: audio focus + countdown overlay → `pressHome()` at zero (cancelled if user pauses first)
  - `pauseIfBlocked()` — MediaSession callback path: `transportControls.pause()` + audio focus + plain overlay
  - `showPlaybackBlockedOverlay()` — calls `MediaPlaybackBlockingOverlayService.show()`
  - `showPlaybackBlockedOverlayWithCountdown()` — calls `MediaPlaybackBlockingOverlayService.showWithCountdown()` with `isStillPlaying` check and `pressHome` as `onExpired`
  - `pressHome()` — fires `ACTION_MAIN / CATEGORY_HOME`; called only from countdown `onExpired` callback
  - `requestAudioFocusForBlocking()` — idempotent `AUDIOFOCUS_GAIN`; may be denied if WhatsApp holds focus
  - `abandonAudioFocus()` — called from `stopEnforcementLoop()` and `onForegroundAppChanged()`
  - `onForegroundAppChanged()` — acquires/releases focus on foreground transition; caches `lastKnownForegroundApp`
  - `updatePlaybackBlocking()` — `effectiveEnabled = enabled && blockedPackages.isNotEmpty()`; fires immediate focus request if blocked app already foregrounded
  - `audioManagerTrackedPackages` — hardcoded `{com.whatsapp}`; gates AudioPlayback attribution

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/MediaPlaybackBlockingOverlayService.kt` *(new)*
  - Full-width dark-red banner, 48 dp icon, "FamilyRules" subtitle, "Odtwarzanie zablokowane" message
  - **Plain mode** (`show()`): auto-dismisses after 5 s; no-op if countdown active
  - **Countdown mode** (`showWithCountdown(context, isStillPlaying, onExpired)`): N→0 badge; checks `isStillPlaying()` each second — false → silent dismiss; zero → `onExpired()` then hide; no-op if countdown already running
  - `TYPE_APPLICATION_OVERLAY`, focusable, `START_NOT_STICKY`

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/ForegroundAppMonitor.kt`
  - `checkForegroundApp()` calls `MediaSessionMonitor.onForegroundAppChanged()` on every foreground transition

- `app/src/main/AndroidManifest.xml`
  - Added `foregroundServiceType="specialUse|mediaPlayback"` to `FamilyRulesCoreService`
  - Added `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission
  - Registered `MediaPlaybackBlockingOverlayService`

- `app/src/main/res/values/strings.xml` — added `media_playback_blocked`
- `app/src/main/res/values-pl/strings.xml` — added `media_playback_blocked`

## Verification

- `./gradlew compileDebugKotlin` — passes, pre-existing warnings only
- Manual test on emulator:
  - YouTube: starts playback → paused via transport controls → plain overlay 5 s → app stays in foreground, user can browse freely
  - WhatsApp video: countdown overlay 5→0 → `pressHome()` → app minimized
  - WhatsApp video paused during countdown → overlay dismisses silently, no home press
  - WhatsApp UI beeps (conversation switching) → not detected (filtered out by `CONTENT_TYPE_SONIFICATION` exclusion)

