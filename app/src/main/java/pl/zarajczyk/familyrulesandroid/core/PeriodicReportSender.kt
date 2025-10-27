package pl.zarajczyk.familyrulesandroid.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState
import pl.zarajczyk.familyrulesandroid.adapter.FamilyRulesClient
import pl.zarajczyk.familyrulesandroid.adapter.Uptime
import pl.zarajczyk.familyrulesandroid.database.AppDb
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class PeriodicReportSender(
    private val coreService: FamilyRulesCoreService,
    private val delayDuration: Duration,
    private val appBlocker: AppBlocker,
    private val appListChangeDetector: AppListChangeDetector,
    private val familyRulesClient: FamilyRulesClient

) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentDeviceState: DeviceState = DeviceState.ACTIVE

    companion object {
        fun install(
            coreService: FamilyRulesCoreService,
            delayDuration: Duration,
            appBlocker: AppBlocker
        ): PeriodicReportSender {
            val appDb = AppDb(coreService)
            val instance = PeriodicReportSender(
                coreService = coreService,
                delayDuration = delayDuration,
                appBlocker = appBlocker,
                appListChangeDetector = AppListChangeDetector(appDb),
                familyRulesClient = FamilyRulesClient(
                    SettingsManager(coreService),
                    appDb
                )

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

    private suspend fun sendInitialClientInfoRequest() = try {
        familyRulesClient.sendClientInfoRequest()
        appListChangeDetector.updateLastSentApps()
    } catch (e: Exception) {
        Log.e("PeriodicReportSender", "Failed to send initial client info: ${e.message}", e)
    }

    private suspend fun runClientInfoInfiniteLoop(isActive: () -> Boolean) {
        while (isActive()) {
            try {
                if (appListChangeDetector.hasAppListChanged()) {
                    Log.i(
                        "PeriodicReportSender",
                        "App list changed, sending client info request"
                    )
                    familyRulesClient.sendClientInfoRequest()
                    appListChangeDetector.updateLastSentApps()
                }
            } catch (e: Exception) {
                Log.e(
                    "PeriodicReportSender",
                    "Failed to check/send app list changes: ${e.message}",
                    e
                )
            }
        }
    }

    private suspend fun runUptimeReportInfiniteLoop(isActive: () -> Boolean) {
        while (isActive()) {
            if (ScreenStatus.isScreenOn(coreService)) {
                try {
                    reportUptime()
                } catch (e: Exception) {
                    Log.e(
                        "PeriodicReportSender",
                        "Failed to perform uptime report: ${e.message}",
                        e
                    )
                }
            }
            delay(delayDuration)
        }
    }

    private suspend fun reportUptime() {
        val uptime = Uptime(
            screenTimeMillis = coreService.getTodayScreenTime(),
            packageUsages = coreService.getTodayPackageUsage()
        )
        val response = familyRulesClient.reportUptime(uptime)
        Log.d("ReportService", "Received device state response: $response")

        // Handle device state changes
        handleDeviceStateChange(response)
    }

    private fun handleDeviceStateChange(newState: DeviceState) {
        if (currentDeviceState != newState) {
            Log.i(
                "PeriodicReportSender",
                "Device state changed from $currentDeviceState to $newState"
            )

            when (newState) {
                DeviceState.ACTIVE -> {
                    // Unblock apps when returning to ACTIVE state
                    if (currentDeviceState == DeviceState.BLOCK_LIMITTED_APPS) {
                        Log.i("PeriodicReportSender", "Unblocking limited apps")
                        appBlocker.unblockLimitedApps()
                    }
                }

                DeviceState.BLOCK_LIMITTED_APPS -> {
                    // Block apps when entering BLOCK_LIMITTED_APPS state
                    Log.i("PeriodicReportSender", "Blocking limited apps")
                    appBlocker.blockLimitedApps()
                }
            }

            currentDeviceState = newState

            // Notify the core service about the state change
            coreService.updateDeviceState(newState)
        }
    }

}