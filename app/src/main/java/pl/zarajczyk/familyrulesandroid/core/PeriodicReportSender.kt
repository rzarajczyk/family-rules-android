package pl.zarajczyk.familyrulesandroid.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val clientInfoDuration: Duration,
    private val appBlocker: AppBlocker,
    private val familyRulesClient: FamilyRulesClient,
    private val settingsManager: SettingsManager,
    private val serverCommandCoordinator: ServerCommandCoordinator,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Last state acknowledged from the server.
    private var currentDeviceState: ActualDeviceState = ActualDeviceState.ACTIVE

    // True only after appBlocker.block() has been called with a non-empty list.
    // Reset to false whenever blocking is disarmed or the service restarts.
    private var blockingArmed: Boolean = false

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
            clientInfoDuration: Duration,
        ): PeriodicReportSender {
            val appDb = AppDb(coreService)
            val settingsManager = SettingsManager(coreService)
            val familyRulesClient = FamilyRulesClient(settingsManager, appDb)
            val instance = PeriodicReportSender(
                coreService = coreService,
                delayDuration = reportDuration,
                clientInfoDuration = clientInfoDuration,
                appBlocker = appBlocker,
                familyRulesClient = familyRulesClient,
                settingsManager = settingsManager,
                serverCommandCoordinator = ServerCommandCoordinator(coreService, appDb, familyRulesClient),
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
    }

    private suspend fun sendInitialClientInfoRequest() {
        try {
            familyRulesClient.sendClientInfoRequest()
            serverCommandCoordinator.retryPendingWork()
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

    private suspend fun runUptimeReportInfiniteLoop(isActive: () -> Boolean) {
        while (isActive()) {
            if (ScreenStatus.isScreenOn(coreService)) {
                try {
                    reportUptime()
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to send report", e)
                }
            }
            delay(delayDuration)
        }
    }

    fun reportUptimeAsync() = scope.launch {
        reportUptime()
    }

    fun sendClientInfoAsync() = scope.launch {
        familyRulesClient.ensureAllAppsAreCached(coreService.getTodayPackageUsage().keys)
        sendInitialClientInfoRequest()
    }

    private suspend fun reportUptime() {
        val foregroundApp = coreService.getForegroundApp()
        // Refresh the active-sessions list right before querying playback state so that
        // the callback registry is always up-to-date regardless of whether the
        // OnActiveSessionsChangedListener fired recently.
        MediaSessionMonitor.refreshActiveSessions("pre-report")
        val mediaPlayingApps = MediaSessionMonitor.getCurrentlyPlayingPackages()
        // Cache app info for media-playing apps so they appear in ClientInfo knownApps even if
        // they have no foreground usage time today (e.g. background audio players).
        familyRulesClient.ensureAllAppsAreCached(mediaPlayingApps)
        val uptime = Uptime(
            screenTimeMillis = coreService.getTodayScreenTime(),
            packageUsages = coreService.getTodayPackageUsage(),
            activeApps = if (foregroundApp != null) setOf(foregroundApp) else emptySet(),
            mediaPlayingApps = mediaPlayingApps,
        )
        try {
            // null means network failure — keep the current local state, do not unblock.
            val response = familyRulesClient.reportUptimeWithCommands(uptime) ?: return
            serverCommandCoordinator.onCommandsReceived(response.serverCommands)
            handleDeviceStateChange(ActualDeviceState.from(response))
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
            MediaSessionMonitor.updatePlaybackBlocking(
                enabled = true,
                blockedPackages = fetched.toSet()
            )
        }
    }

    private fun persistBlockedPlaybackApps(apps: List<String>) {
        settingsManager.setStringSet(KEY_CACHED_BLOCKED_PLAYBACK_APPS, apps.toSet())
    }

}
