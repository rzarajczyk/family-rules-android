package pl.zarajczyk.familyrulesandroid.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import pl.zarajczyk.familyrulesandroid.adapter.ActualDeviceState
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState
import pl.zarajczyk.familyrulesandroid.adapter.FamilyRulesClient
import pl.zarajczyk.familyrulesandroid.adapter.Uptime
import pl.zarajczyk.familyrulesandroid.database.AppDb
import pl.zarajczyk.familyrulesandroid.utils.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val TAG = "PeriodicReportSender"
private const val KEY_CACHED_BLOCKED_APPS = "cachedBlockedApps"
private const val KEY_CACHED_BLOCKED_PLAYBACK_APPS = "cachedBlockedPlaybackApps"

class PeriodicReportSender(
    private val coreService: FamilyRulesCoreService,
    private val delayDuration: Duration,
    private val screenOffDelayDuration: Duration,
    private val clientInfoDuration: Duration,
    private val locationCheckDuration: Duration,
    private val appBlocker: AppBlocker,
    private val familyRulesClient: FamilyRulesClient,
    private val settingsManager: SettingsManager,
    private val serverCommandCoordinator: ServerCommandCoordinator,
    private val locationTracker: LocationTracker,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Last state acknowledged from the server.
    private var currentDeviceState: ActualDeviceState = ActualDeviceState.ACTIVE

    // True only after appBlocker.block() has been called with a non-empty list.
    // Reset to false whenever blocking is disarmed or the service restarts.
    private var blockingArmed: Boolean = false

    // Signal channel: when the screen turns on, notify the report loop to wake up
    // from its long screen-off delay and send an immediate report.
    private val screenOnSignal = Channel<Unit>(Channel.CONFLATED)

    // Serializes report execution so that a user-triggered manual refresh and the
    // periodic loop never run reportUptime() concurrently. Without this, the two
    // could interleave inside handleDeviceStateChange and double-arm blocking or
    // race on currentDeviceState / blockingArmed.
    private val reportMutex = Mutex()

    // Last known-good blocked app list. Restored from SharedPreferences on startup
    // so that a restart + failed first fetch still has a fallback (Problem 7).
    private var cachedBlockedApps: List<String> =
        settingsManager.getStringSet(KEY_CACHED_BLOCKED_APPS).toList()

    // Last known-good blocked playback app list. Mirrors cachedBlockedApps pattern.
    private var cachedBlockedPlaybackApps: List<String> =
        settingsManager.getStringSet(KEY_CACHED_BLOCKED_PLAYBACK_APPS).toList()

    companion object {
        fun install(
            coreService: FamilyRulesCoreService,
            appBlocker: AppBlocker,
            reportDuration: Duration,
            screenOffReportDuration: Duration,
            clientInfoDuration: Duration,
            locationCheckDuration: Duration,
            locationTracker: LocationTracker,
        ): PeriodicReportSender {
            val appDb = AppDb(coreService)
            val settingsManager = SettingsManager(coreService)
            val familyRulesClient = FamilyRulesClient(settingsManager, appDb)
            val instance = PeriodicReportSender(
                coreService = coreService,
                delayDuration = reportDuration,
                screenOffDelayDuration = screenOffReportDuration,
                clientInfoDuration = clientInfoDuration,
                locationCheckDuration = locationCheckDuration,
                appBlocker = appBlocker,
                familyRulesClient = familyRulesClient,
                settingsManager = settingsManager,
                serverCommandCoordinator = ServerCommandCoordinator(coreService, appDb, familyRulesClient),
                locationTracker = locationTracker,
            )
            instance.start()
            return instance
        }
    }

    fun start() {
        scope.launch {
            sendInitialClientInfoRequest()
        }

        scope.launch {
            delay(2.minutes)
            runClientInfoInfiniteLoop { isActive }
        }

        scope.launch {
            runUptimeReportInfiniteLoop { isActive }
        }

        scope.launch {
            runLocationCheckInfiniteLoop { isActive }
        }
    }

    private suspend fun sendInitialClientInfoRequest() {
        try {
            FcmTokenRegistrar.refreshToken(settingsManager) {
                scope.launch {
                    try {
                        familyRulesClient.sendClientInfoRequest()
                        serverCommandCoordinator.retryPendingWork()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to send initial client info after FCM token refresh: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to send initial client info: ${e.message}", e)
        }
    }

    private suspend fun runClientInfoInfiniteLoop(isActive: () -> Boolean) {
        while (isActive()) {
            try {
                if (ScreenStatus.isScreenOn(coreService)) {
                    familyRulesClient.ensureAllAppsAreCached(coreService.getTodayPackageUsage().keys)
                    familyRulesClient.sendClientInfoRequest()
                    serverCommandCoordinator.retryPendingWork()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send client info", e)
            }
            delay(clientInfoDuration)
        }
    }

    private suspend fun runLocationCheckInfiniteLoop(isActive: () -> Boolean) {
        while (isActive()) {
            try {
                if (!ScreenStatus.isScreenOn(coreService) && locationTracker.hasLocationPermission()) {
                    val location = locationTracker.getCurrentLocation()
                    if (location != null) {
                        val (lat, lng) = location
                        if (locationTracker.hasMoved(lat, lng)) {
                            Logger.i(TAG, "Location changed while screen off - forcing report")
                            reportUptime(isOnline = false)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to check location", e)
            }
            delay(locationCheckDuration)
        }
    }

    private suspend fun runUptimeReportInfiniteLoop(isActive: () -> Boolean) {
        while (isActive()) {
            val screenOn = ScreenStatus.isScreenOn(coreService)
            try {
                reportUptime(isOnline = screenOn)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send report", e)
            }
            if (screenOn) {
                delay(delayDuration)
            } else {
                // Wait for the full screen-off delay, but allow an early exit if the
                // screen turns back on. Without this, a brief screen-off followed by
                // screen-on leaves the loop sleeping for the remainder of the 60-minute
                // delay, making the app blind to server state changes for up to an hour.
                withTimeoutOrNull(screenOffDelayDuration) {
                    screenOnSignal.receive()
                }
            }
        }
    }

    fun reportUptimeAsync() = scope.launch {
        reportUptime(isOnline = ScreenStatus.isScreenOn(coreService))
    }

    /**
     * Forces an immediate report and returns the resulting device state, or null if the
     * report did not reach the server (network failure). A null result lets the caller
     * distinguish a genuine refresh from a stale, locally-cached state.
     */
    suspend fun reportUptimeSync(): ActualDeviceState? {
        val reachedServer = reportUptime(isOnline = ScreenStatus.isScreenOn(coreService))
        return if (reachedServer) currentDeviceState else null
    }

    /**
     * Called when the screen turns on. Interrupts the long screen-off delay in the
     * report loop so that an immediate report is sent, allowing the app to discover
     * server-side state changes without waiting up to 60 minutes.
     */
    fun notifyScreenOn() {
        screenOnSignal.trySend(Unit)
    }

    fun sendClientInfoAsync() = scope.launch {
        familyRulesClient.ensureAllAppsAreCached(coreService.getTodayPackageUsage().keys)
        sendInitialClientInfoRequest()
    }

    /**
     * Sends a single uptime report and applies the resulting device state.
     * Returns true if the server was reached, false on network failure (in which case
     * the current local state is left untouched and blocking is not disarmed).
     *
     * Serialized behind [reportMutex] so a manual refresh and the periodic loop can
     * never run this concurrently and race on currentDeviceState / blockingArmed.
     */
    private suspend fun reportUptime(isOnline: Boolean = true): Boolean = reportMutex.withLock {
        val foregroundApp = coreService.getForegroundApp()
        // Refresh the active-sessions list right before querying playback state so that
        // the callback registry is always up-to-date regardless of whether the
        // OnActiveSessionsChangedListener fired recently.
        MediaSessionMonitor.refreshActiveSessions("pre-report")
        val mediaPlayingApps = MediaSessionMonitor.getCurrentlyPlayingPackages()
        // Cache app info for media-playing apps so they appear in ClientInfo knownApps even if
        // they have no foreground usage time today (e.g. background audio players).
        familyRulesClient.ensureAllAppsAreCached(mediaPlayingApps)

        // Get location for this report
        val location = if (locationTracker.hasLocationPermission()) {
            locationTracker.getLastCachedLocation() ?: locationTracker.getCurrentLocation()
        } else {
            null
        }

        val latitude = location?.first
        val longitude = location?.second

        val uptime = Uptime(
            screenTimeMillis = coreService.getTodayScreenTime(),
            packageUsages = coreService.getTodayPackageUsage(),
            activeApps = if (foregroundApp != null) setOf(foregroundApp) else emptySet(),
            mediaPlayingApps = mediaPlayingApps,
            latitude = latitude,
            longitude = longitude,
        )
        try {
            // null means network failure — keep the current local state, do not unblock.
            val response = familyRulesClient.reportUptimeWithCommands(uptime, isOnline = isOnline)
                ?: return@withLock false
            serverCommandCoordinator.onCommandsReceived(response.serverCommands)
            handleDeviceStateChange(ActualDeviceState.from(response))

            // Mark location as reported after successful send
            if (latitude != null && longitude != null) {
                locationTracker.markReported(latitude, longitude)
            }
            true
        } finally {
            MediaSessionMonitor.enforcePlaybackBlocking()
        }
    }

    private suspend fun handleDeviceStateChange(newState: ActualDeviceState) {
        val stateChanged = currentDeviceState != newState

        if (stateChanged) {
            Logger.i(TAG, "Device state changed from ${currentDeviceState.state} to ${newState.state}")
        }

        when (newState.state) {
            DeviceState.ACTIVE -> {
                if (stateChanged &&
                    (currentDeviceState.state == DeviceState.BLOCK_RESTRICTED_APPS ||
                     currentDeviceState.state == DeviceState.BLOCK_RESTRICTED_APPS_WITH_TIMEOUT)) {
                    Logger.i(TAG, "Unblocking restricted apps - returning to ACTIVE state")
                    CountdownOverlayService.hideCountdown(coreService)
                    appBlocker.unblock()
                    blockingArmed = false
                }
                // Disable playback blocking when device becomes active.
                MediaSessionMonitor.updatePlaybackBlocking(enabled = false, blockedPackages = emptySet())
            }

            DeviceState.BLOCK_RESTRICTED_APPS -> {
                if (stateChanged) {
                    // Fresh transition into blocking — fetch and arm immediately.
                    armBlocking()
                    MediaSessionMonitor.updatePlaybackBlocking(
                        enabled = true,
                        blockedPackages = resolveBlockedPlaybackApps().toSet()
                    )
                } else if (!blockingArmed) {
                    // Problem 3: previous arm attempt failed; retry every cycle.
                    Logger.i(TAG, "Blocking not yet armed - retrying")
                    armBlocking()
                    MediaSessionMonitor.updatePlaybackBlocking(
                        enabled = true,
                        blockedPackages = resolveBlockedPlaybackApps().toSet()
                    )
                } else {
                    // Problem 4: already armed, but refresh the list in case the server-side
                    // group changed while the device state name stayed the same.
                    refreshBlockedAppsIfChanged()
                    refreshBlockedPlaybackAppsIfChanged()
                }
            }

            DeviceState.BLOCK_RESTRICTED_APPS_WITH_TIMEOUT -> {
                if (stateChanged) {
                    // Fresh transition — show countdown then arm.
                    Logger.i(TAG, "Starting countdown before blocking apps")
                    val appList = resolveBlockedApps()
                    CountdownOverlayService.showCountdown(coreService) {
                        Logger.i(TAG, "Countdown complete - blocking apps")
                        appBlocker.block(appList)
                        blockingArmed = appList.isNotEmpty()
                        // Enable playback blocking only after countdown completes.
                        scope.launch {
                            MediaSessionMonitor.updatePlaybackBlocking(
                                enabled = true,
                                blockedPackages = resolveBlockedPlaybackApps().toSet()
                            )
                            MediaSessionMonitor.enforcePlaybackBlocking()
                        }
                    }
                    // blockingArmed stays false until the countdown callback fires.
                    // Keep playback blocking disabled during countdown.
                    MediaSessionMonitor.updatePlaybackBlocking(enabled = false, blockedPackages = emptySet())
                } else if (!blockingArmed) {
                    // Problem 3: countdown may have been killed; retry arming directly.
                    Logger.i(TAG, "Blocking not yet armed after timeout state - retrying arm")
                    armBlocking()
                    MediaSessionMonitor.updatePlaybackBlocking(
                        enabled = true,
                        blockedPackages = resolveBlockedPlaybackApps().toSet()
                    )
                } else {
                    // Problem 4: refresh list while already in blocking state.
                    refreshBlockedAppsIfChanged()
                    refreshBlockedPlaybackAppsIfChanged()
                }
            }
        }

        if (stateChanged) {
            currentDeviceState = newState
            coreService.updateDeviceState(newState)
        }

    }

    /**
     * Fetch the blocked app list, fall back to cache, then call appBlocker.block().
     * Updates cachedBlockedApps and blockingArmed.
     */
    private suspend fun armBlocking() {
        val appList = resolveBlockedApps()
        if (appList.isNotEmpty()) {
            appBlocker.block(appList)
            blockingArmed = true
            Logger.i(TAG, "Blocking armed with ${appList.size} apps")
        } else {
            Logger.w(TAG, "Could not arm blocking: no apps available (will retry next cycle)")
        }
    }

    /**
     * Fetch the latest list from the server. If the list has changed, re-arm immediately.
     * Covers Problem 4: server-side group contents changed while device state stayed the same.
     */
    private suspend fun refreshBlockedAppsIfChanged() {
        val freshList = familyRulesClient.getBlockedApps() ?: return  // fetch failed; keep current
        // Persist unconditionally — an empty list is a valid intentional server response.
        persistBlockedApps(freshList)
        if (freshList.toSet() != cachedBlockedApps.toSet()) {
            Logger.i(TAG, "Blocked app list changed - re-arming (${cachedBlockedApps.size} -> ${freshList.size} apps)")
            cachedBlockedApps = freshList
            appBlocker.block(freshList)
            blockingArmed = freshList.isNotEmpty()
        }
    }

    /**
     * Returns the best available blocked app list:
     *   1. Fresh fetch from server (also updates cache).
     *   2. Last known-good cache (Problem 7 fallback).
     *   3. Empty list — caller must treat this as "arm failed".
     */
    private suspend fun resolveBlockedApps(): List<String> {
        val fetched = familyRulesClient.getBlockedApps()
        return if (fetched != null) {
            // Persist unconditionally — an empty list from the server is intentional
            // and must overwrite any stale non-empty cache (fixes isNotEmpty() guard bug).
            persistBlockedApps(fetched)
            cachedBlockedApps = fetched
            fetched
        } else {
            // Fetch failed — use persisted cache as fallback.
            if (cachedBlockedApps.isNotEmpty()) {
                Logger.w(TAG, "getBlockedApps() failed - using cached list (${cachedBlockedApps.size} apps)")
            }
            cachedBlockedApps
        }
    }

    private fun persistBlockedApps(apps: List<String>) {
        settingsManager.setStringSet(KEY_CACHED_BLOCKED_APPS, apps.toSet())
    }

    /**
     * Returns the best available blocked playback app list:
     *   1. Fresh fetch from server (also updates cache).
     *   2. Last known-good cache.
     *   3. Empty list — caller must treat this as "no playback blocking targets available".
     */
    private suspend fun resolveBlockedPlaybackApps(): List<String> {
        val fetched = familyRulesClient.getBlockedPlaybackApps()
        return if (fetched != null) {
            persistBlockedPlaybackApps(fetched)
            cachedBlockedPlaybackApps = fetched
            fetched
        } else {
            if (cachedBlockedPlaybackApps.isNotEmpty()) {
                Logger.w(
                    TAG,
                    "getBlockedPlaybackApps() failed - using cached list (${cachedBlockedPlaybackApps.size} apps)"
                )
            }
            cachedBlockedPlaybackApps
        }
    }

    /**
     * Fetch the latest blocked playback list. If the list changed, update playback blocking
     * immediately while keeping enforcement enabled.
     */
    private suspend fun refreshBlockedPlaybackAppsIfChanged() {
        val fetched = familyRulesClient.getBlockedPlaybackApps() ?: return
        persistBlockedPlaybackApps(fetched)
        if (fetched.toSet() != cachedBlockedPlaybackApps.toSet()) {
            Logger.i(
                TAG,
                "Blocked playback app list changed - refreshing (${cachedBlockedPlaybackApps.size} -> ${fetched.size} apps)"
            )
            cachedBlockedPlaybackApps = fetched
        }
        // Always call updatePlaybackBlocking — even when the list is unchanged — so that a dead
        // enforcement loop (e.g. killed by a NotificationListenerService disconnect/reconnect cycle)
        // is restarted without waiting for a list change.
        MediaSessionMonitor.updatePlaybackBlocking(
            enabled = true,
            blockedPackages = cachedBlockedPlaybackApps.toSet()
        )
    }

    private fun persistBlockedPlaybackApps(apps: List<String>) {
        settingsManager.setStringSet(KEY_CACHED_BLOCKED_PLAYBACK_APPS, apps.toSet())
    }

}
