package pl.zarajczyk.familyrulesandroid.core

import android.util.Log
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

class PeriodicReportSender(
    private val coreService: FamilyRulesCoreService,
    private val delayDuration: Duration,
    private val clientInfoDuration: Duration,
    private val appBlocker: AppBlocker,
    private val familyRulesClient: FamilyRulesClient

) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentDeviceState: ActualDeviceState = ActualDeviceState.ACTIVE

    companion object {
        fun install(
            coreService: FamilyRulesCoreService,
            appBlocker: AppBlocker,
            reportDuration: Duration,
            clientInfoDuration: Duration,
        ): PeriodicReportSender {
            val appDb = AppDb(coreService)
            val instance = PeriodicReportSender(
                coreService = coreService,
                delayDuration = reportDuration,
                clientInfoDuration = clientInfoDuration,
                appBlocker = appBlocker,
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

    private suspend fun sendInitialClientInfoRequest() {
        try {
            familyRulesClient.sendClientInfoRequest()
        } catch (e: Exception) {
            Logger.e("PeriodicReportSender", "Failed to send initial client info: ${e.message}", e)
        }
    }

    private suspend fun runClientInfoInfiniteLoop(isActive: () -> Boolean) {
        while (isActive()) {
            try {
                if (ScreenStatus.isScreenOn(coreService)) {
                    Logger.i("PeriodicReportSender", "Sending client info request")
                    familyRulesClient.sendClientInfoRequest()
                }
            } catch (e: Exception) {
                Logger.e("PeriodicReportSender", "Failed to send client info", e)
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
                    Logger.e("PeriodicReportSender", "Failed to send report", e)
                }
            }
            delay(delayDuration)
        }
    }

    fun reportUptimeAsync() = scope.launch {
        reportUptime()
    }

    fun sendClientInfoAsync() = scope.launch {
        sendInitialClientInfoRequest()
    }

    private suspend fun reportUptime() {
        val uptime = Uptime(
            screenTimeMillis = coreService.getTodayScreenTime(),
            packageUsages = coreService.getTodayPackageUsage()
        )
        val response = familyRulesClient.reportUptime(uptime)
        Logger.i("ReportService", "Uptime reported to server, received device state: ${response.state}")

        // Handle device state changes
        handleDeviceStateChange(response)
    }

    private fun handleDeviceStateChange(newState: ActualDeviceState) {
        if (currentDeviceState != newState) {
            Logger.i(
                "PeriodicReportSender",
                "Device state changed from ${currentDeviceState.state} to ${newState.state}"
            )

            when (newState.state) {
                DeviceState.ACTIVE -> {
                    // Unblock apps when returning to ACTIVE state
                    if (currentDeviceState.state == DeviceState.BLOCK_RESTRICTED_APPS) {
                        Logger.i("PeriodicReportSender", "Unblocking restricted apps - returning to ACTIVE state")
                        appBlocker.unblock()
                    }
                }

                DeviceState.BLOCK_RESTRICTED_APPS -> {
                    // Block apps when entering BLOCK_RESTRICTED_APPS state for the first time
                    if (currentDeviceState != newState) {
                        val appGroupId = newState.extra
                        if (appGroupId != null) {
                            Logger.w("PeriodicReportSender", "Blocking apps - fetching group: $appGroupId")
                            scope.launch {
                                try {
                                    val appList = familyRulesClient.getAppGroupReport(appGroupId)
                                    appBlocker.block(appList)
                                    Logger.w("PeriodicReportSender", "Blocking ${appList.size} restricted apps")
                                } catch (e: Exception) {
                                    Logger.e("PeriodicReportSender", "Failed to fetch app group: ${e.message}", e)
                                }
                            }
                        } else {
                            Logger.w("PeriodicReportSender", "No appGroupId provided in BLOCK_RESTRICTED_APPS state")
                        }
                    }
                }
            }

            currentDeviceState = newState

            // Notify the core service about the state change
            coreService.updateDeviceState(newState)
        }
    }

}