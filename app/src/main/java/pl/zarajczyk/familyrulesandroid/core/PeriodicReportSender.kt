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

class PeriodicReportSender(
    private val coreService: FamilyRulesCoreService,
    private val delayDuration: Duration,
    private val clientInfoDuration: Duration,
    private val appBlocker: AppBlocker,
    private val familyRulesClient: FamilyRulesClient,
    private val settingsManager: SettingsManager,
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

    companion object {
        fun install(
            coreService: FamilyRulesCoreService,
            appBlocker: AppBlocker,
            reportDuration: Duration,
            clientInfoDuration: Duration,
        ): PeriodicReportSender {
            val appDb = AppDb(coreService)
            val settingsManager = SettingsManager(coreService)
            val instance = PeriodicReportSender(
                coreService = coreService,
                delayDuration = reportDuration,
                clientInfoDuration = clientInfoDuration,
                appBlocker = appBlocker,
                familyRulesClient = FamilyRulesClient(settingsManager, appDb),
                settingsManager = settingsManager,
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
        val uptime = Uptime(
            screenTimeMillis = coreService.getTodayScreenTime(),
            packageUsages = coreService.getTodayPackageUsage(),
            activeApps = if (foregroundApp != null) setOf(foregroundApp) else emptySet()
        )
        // null means network failure — keep the current local state, do not unblock.
        val response = familyRulesClient.reportUptime(uptime) ?: return
        handleDeviceStateChange(response)
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
            }

            DeviceState.BLOCK_RESTRICTED_APPS -> {
                if (stateChanged) {
                    // Fresh transition into blocking — fetch and arm immediately.
                    armBlocking()
                } else if (!blockingArmed) {
                    // Problem 3: previous arm attempt failed; retry every cycle.
                    Logger.i(TAG, "Blocking not yet armed - retrying")
                    armBlocking()
                } else {
                    // Problem 4: already armed, but refresh the list in case the server-side
                    // group changed while the device state name stayed the same.
                    refreshBlockedAppsIfChanged()
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
                    }
                    // blockingArmed stays false until the countdown callback fires.
                } else if (!blockingArmed) {
                    // Problem 3: countdown may have been killed; retry arming directly.
                    Logger.i(TAG, "Blocking not yet armed after timeout state - retrying arm")
                    armBlocking()
                } else {
                    // Problem 4: refresh list while already in blocking state.
                    refreshBlockedAppsIfChanged()
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
}
