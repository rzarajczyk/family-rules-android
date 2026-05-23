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

### Attempt 4 — Switch to `AUDIOFOCUS_GAIN` + fix already-foregrounded gap (current)

Two changes on top of Attempt 3:

**4a. `AUDIOFOCUS_GAIN_TRANSIENT` → `AUDIOFOCUS_GAIN`**

`AUDIOFOCUS_GAIN` is the permanent ownership signal: apps receiving `AUDIOFOCUS_LOSS` (not `AUDIOFOCUS_LOSS_TRANSIENT`) are expected to stop playback entirely rather than pause and auto-resume. For a parental-control use case this is semantically correct — blocking should persist for the entire duration the app is foregrounded, not just briefly. The tradeoff: a compliant app that was playing in the background will stop rather than pause, and may not auto-resume when focus is released (the user may need to restart it manually).

**4b. Immediate focus acquisition when blocking activates with a blocked app already foregrounded**

Added `lastKnownForegroundApp` field to `MediaSessionMonitor`, updated on every `onForegroundAppChanged()` call. In `updatePlaybackBlocking()`, after the enforcement loop is started, focus is requested immediately if `lastKnownForegroundApp` is in the blocked list. This is guarded: if a non-blocked app is currently foregrounded, focus is not requested and no audio disruption occurs.

**Outcome: pending device test.**

Audio focus is still a voluntary protocol, so WhatsApp ignoring `AUDIOFOCUS_LOSS` entirely remains a possibility. If device testing confirms WhatsApp still plays uninterrupted, the next approach to consider is `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE)` scoped to the blocked app's foreground window — this operates at the audio mixer level below app participation and cannot be bypassed by the app.

## Deferred Next Steps

These items are intentionally deferred until after testing the current `AUDIOFOCUS_GAIN` build on-device. The immediate goal is to confirm whether WhatsApp cooperates with permanent focus loss.

### 1. Track real audio-focus loss events

`requestAudioFocusForBlocking()` currently uses an empty `OnAudioFocusChangeListener`. That means `audioFocusHeld` only reflects what this process last requested, not what the system currently grants. If another app or the system takes focus away, we do not clear `audioFocusHeld`, so a later reacquire attempt may be skipped incorrectly.

**Follow-up:** implement a real focus-change listener that logs focus changes and clears local state on focus loss.

### 2. Remove the startup race around `lastKnownForegroundApp`

The "already foregrounded" fix depends on `lastKnownForegroundApp`, but that value is populated asynchronously by `ForegroundAppMonitor`. During a fresh transition into blocking, `updatePlaybackBlocking()` can run before the first foreground-monitor tick, leaving `lastKnownForegroundApp == null` and delaying the first focus request by up to one poll interval.

**Follow-up:** provide `MediaSessionMonitor` with the current foreground app synchronously when playback blocking is enabled, instead of waiting for the monitor loop to populate it.

### 3. Decouple playback blocking from app blocking

Foreground transitions are currently reported by `ForegroundAppMonitor`, which is owned by `AppBlocker` and only runs when the normal blocked-app list is armed. If playback-blocked packages exist while the regular blocked-app list is empty, non-`MediaSession` apps such as WhatsApp will not trigger `onForegroundAppChanged()` at all.

**Follow-up:** start foreground monitoring whenever either app blocking or playback blocking is active, or move foreground-app observation out of `AppBlocker` into a shared always-available component.

### 4. Validate collateral impact on compliant background players

`AUDIOFOCUS_GAIN` is intentionally more aggressive than `AUDIOFOCUS_GAIN_TRANSIENT`. Compliant background players may stop instead of pause and may require manual restart after focus is released.

**Follow-up:** verify this behavior explicitly with YT Music and any other common background player before treating the implementation as final.

## Files Changed

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/MediaSessionMonitor.kt`
  - Added `requestAudioFocusForBlocking()` — idempotent, acquires focus when needed
  - Added `abandonAudioFocus()` — called from `stopEnforcementLoop()` and `onForegroundAppChanged()`
  - Added `onForegroundAppChanged()` — acquires/releases audio focus based on whether the foreground app is blocked; caches package in `lastKnownForegroundApp`
  - Wired `requestAudioFocusForBlocking()` into `enforcePlaybackBlocking()` and `pauseIfBlocked()` for MediaSession-based apps
  - Added `audioManager`, `audioFocusRequest`, `audioFocusHeld`, `lastKnownForegroundApp` fields; `audioManager` initialized in `install()`
  - Focus type: `AUDIOFOCUS_GAIN` (permanent ownership)
  - `updatePlaybackBlocking()` immediately acquires focus if a blocked app is already foregrounded when blocking activates

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/ForegroundAppMonitor.kt`
  - `checkForegroundApp()` now calls `MediaSessionMonitor.onForegroundAppChanged()` on every foreground app transition

## Verification

- `./gradlew compileDebugKotlin` — passes, pre-existing warnings only
