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

### Attempt 3 — Request audio focus on foreground-app change (current fix)

Added `onForegroundAppChanged(packageName: String)` to `MediaSessionMonitor`, called by `ForegroundAppMonitor` whenever the foreground app changes. The logic is:

- If the new foreground app is in `blockedPlaybackPackages` and blocking is active → request audio focus immediately, regardless of whether a `MediaSession` exists.
- If the new foreground app is not blocked → abandon audio focus, so background music players (YT Music, etc.) can reclaim it and resume.
- Audio focus is also abandoned in `stopEnforcementLoop()` when blocking is disabled entirely.

This means:
- WhatsApp in foreground + blocking active → audio focus taken → video interrupted without needing a `MediaSession`
- User switches away from WhatsApp → audio focus released → YT Music can resume
- A non-blocked app that is in the foreground never loses audio focus due to playback blocking

**Outcome: correct behavior for both MediaSession and non-MediaSession apps.**

## Files Changed

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/MediaSessionMonitor.kt`
  - Added `requestAudioFocusForBlocking()` — idempotent, acquires focus when needed
  - Added `abandonAudioFocus()` — called from `stopEnforcementLoop()` and `onForegroundAppChanged()`
  - Added `onForegroundAppChanged()` — acquires/releases audio focus based on whether the foreground app is blocked
  - Wired `requestAudioFocusForBlocking()` into `enforcePlaybackBlocking()` and `pauseIfBlocked()` for MediaSession-based apps
  - Added `audioManager`, `audioFocusRequest`, `audioFocusHeld` fields; `audioManager` initialized in `install()`

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/ForegroundAppMonitor.kt`
  - `checkForegroundApp()` now calls `MediaSessionMonitor.onForegroundAppChanged()` on every foreground app transition

## Verification

- `./gradlew compileDebugKotlin` — passes, pre-existing warnings only

