package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.zarajczyk.familyrulesandroid.adapter.FamilyRulesClient
import pl.zarajczyk.familyrulesandroid.database.AppDb
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class PeriodicReportSender(
    private val context: Context,
    settingsManager: SettingsManager,
    private val periodicUptimeChecker: PeriodicUptimeChecker,
    private val delayDuration: Duration,
    private val appDb: AppDb
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val familyRulesClient: FamilyRulesClient = FamilyRulesClient(context, settingsManager, appDb)
    private val appListChangeDetector = AppListChangeDetector(appDb)
    private val appBlocker = AppBlocker(context)
    private var currentDeviceState: pl.zarajczyk.familyrulesandroid.adapter.DeviceState = pl.zarajczyk.familyrulesandroid.adapter.DeviceState.ACTIVE
    private var coreService: FamilyRulesCoreService? = null

    companion object {
        fun install(
            context: Context,
            settingsManager: SettingsManager,
            periodicUptimeChecker: PeriodicUptimeChecker,
            delayMillis: Duration,
            appDb: AppDb
        ) {
            PeriodicReportSender(context, settingsManager, periodicUptimeChecker, delayMillis, appDb).start()
        }
    }
    
    fun setCoreService(service: FamilyRulesCoreService) {
        coreService = service
    }

    fun start() {
        // Send initial client info request
        scope.launch {
            try {
                familyRulesClient.sendClientInfoRequest()
                appListChangeDetector.updateLastSentApps()
            } catch (e: Exception) {
                Log.e("PeriodicReportSender", "Failed to send initial client info: ${e.message}", e)
            }
        }
        
        scope.launch {
            while (isActive) {
                delay(10.minutes)
                try {
                    if (appListChangeDetector.hasAppListChanged()) {
                        Log.i("PeriodicReportSender", "App list changed, sending client info request")
                        familyRulesClient.sendClientInfoRequest()
                        appListChangeDetector.updateLastSentApps()
                    }
                } catch (e: Exception) {
                    Log.e("PeriodicReportSender", "Failed to check/send app list changes: ${e.message}", e)
                }
            }
        }
        
        // Start the regular uptime reporting
        scope.launch {
            while (isActive) {
                if (ScreenStatus.isScreenOn(context)) {
                    try {
                        performTask()
                    } catch (e: Exception) {
                        Log.e("PeriodicReportSender", "Failed to perform uptime report: ${e.message}", e)
                    }
                }
                delay(delayDuration)
            }
        }
    }

    private suspend fun performTask() {
        val uptime = periodicUptimeChecker.getUptime()
        val response = familyRulesClient.reportUptime(uptime)
        Log.d("ReportService", "Received device state response: $response")
        
        // Handle device state changes
        handleDeviceStateChange(response)
    }
    
    private fun handleDeviceStateChange(newState: pl.zarajczyk.familyrulesandroid.adapter.DeviceState) {
        if (currentDeviceState != newState) {
            Log.i("PeriodicReportSender", "Device state changed from $currentDeviceState to $newState")
            
            when (newState) {
                pl.zarajczyk.familyrulesandroid.adapter.DeviceState.ACTIVE -> {
                    // Unblock apps when returning to ACTIVE state
                    if (currentDeviceState == pl.zarajczyk.familyrulesandroid.adapter.DeviceState.BLOCK_LIMITTED_APPS) {
                        Log.i("PeriodicReportSender", "Unblocking limited apps")
                        appBlocker.unblockLimitedApps()
                    }
                }
                pl.zarajczyk.familyrulesandroid.adapter.DeviceState.BLOCK_LIMITTED_APPS -> {
                    // Block apps when entering BLOCK_LIMITTED_APPS state
                    Log.i("PeriodicReportSender", "Blocking limited apps")
                    appBlocker.blockLimitedApps()
                }
            }
            
            currentDeviceState = newState
            
            // Notify the core service about the state change
            coreService?.updateDeviceState(newState)
        }
    }

}