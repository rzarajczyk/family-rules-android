## Problem

When the server returned a `BLOCK_RESTRICTED_APPS_WITH_TIMEOUT` state with a non-empty blocked-apps list, playback blocking was supposed to suppress only the explicitly listed apps. Instead, all media playback on the device was interrupted — including apps that were not on the blocked list at all (e.g. YT Music while only `com.google.android.youtube` was blocked).

A secondary failure: when the server returned `BLOCK_RESTRICTED_APPS_WITH_TIMEOUT` with an **empty** blocked-playback-apps list, `enabled=true` alone was still sufficient to trigger the enforcement loop and audio focus grab, causing global playback suppression even though no app was supposed to be blocked.

## Root Cause

The enforcement mechanism combined two distinct suppression strategies:

1. **`transportControls.pause()`** — targeted per-package pause via the MediaSession API.
2. **`AudioManager.requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT)`** — a system-wide audio focus request.

Audio focus is an OS-level mechanism. Holding `AUDIOFOCUS_GAIN_TRANSIENT` broadcasts a loss event to every app currently holding focus, regardless of which app is blocked. There is no way to scope audio focus to a single package.

The original implementation held audio focus for the entire duration of a blocking period. Any app that started or resumed playback during that window would immediately receive `AUDIOFOCUS_LOSS` and pause — regardless of whether it appeared in the blocked list.

## Approaches Tried

### Attempt 1 — Remove audio focus entirely

Removed all audio focus logic from `MediaSessionMonitor`: deleted `requestAudioFocus()`, `abandonAudioFocus()`, all related fields (`audioManager`, `audioFocusRequest`, `audioFocusHeld`), and all call sites.

**Outcome: regression.** Without audio focus, `transportControls.pause()` alone was insufficient to keep YT Music suppressed. YT Music aggressively re-requests audio focus after being paused and resumes playback within milliseconds. The `onPlaybackStateChanged` callback fires on state transitions, so once the app is already in `STATE_PAUSED` it stops triggering the callback. The 1-second enforcement tick is too slow to reliably suppress a fast-resuming app.

This confirmed that audio focus was not just incidental — it was the mechanism that actually prevented the blocked app from reclaiming audio. The problem was not audio focus per se, but holding it globally and unconditionally.

### Attempt 2 — Request audio focus only when a blocked app is confirmed playing (current fix)

Changed `enforcePlaybackBlocking()` and `pauseIfBlocked()` so that `requestAudioFocusForBlocking()` is called only after filtering confirms that at least one blocked app is actively playing (`STATE_PLAYING` or `STATE_BUFFERING`).

Audio focus is:
- **Not requested** during setup or when blocking becomes active — only when a blocked app is actually caught playing.
- **Held** until blocking is disabled (abandoned in `stopEnforcementLoop()`), so the blocked app cannot reclaim focus between enforcement ticks.
- **Not requested at all** when no blocked app is playing in a given tick.

Additionally fixed the empty-list case: `updatePlaybackBlocking()` now sets `effectiveEnabled = enabled && blockedPackages.isNotEmpty()`, so an empty blocked list never starts the enforcement loop.

**Outcome: correct behavior.** Audio focus is only taken away from an app that is explicitly listed as blocked and is confirmed to be playing at that moment. Apps not on the blocked list are never interrupted.

## Files Changed

- `app/src/main/java/pl/zarajczyk/familyrulesandroid/core/MediaSessionMonitor.kt`
  - Added `requestAudioFocusForBlocking()` — idempotent, only acquires focus when a blocked app is playing
  - Added `abandonAudioFocus()` — called from `stopEnforcementLoop()` when blocking ends
  - Wired both into `enforcePlaybackBlocking()` and `pauseIfBlocked()` after the blocked-package filter
  - Added `audioManager`, `audioFocusRequest`, `audioFocusHeld` fields; `audioManager` initialized in `install()`

## Verification

- `./gradlew compileDebugKotlin` — passes, pre-existing warnings only
