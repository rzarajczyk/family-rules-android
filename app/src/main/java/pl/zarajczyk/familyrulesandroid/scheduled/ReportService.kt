package pl.zarajczyk.familyrulesandroid.scheduled

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.zarajczyk.familyrulesandroid.adapter.FamilyRulesClient
import pl.zarajczyk.familyrulesandroid.gui.ScreenStatus
import pl.zarajczyk.familyrulesandroid.gui.SettingsManager

class ReportService(
    private val context: Context,
    settingsManager: SettingsManager,
    private val uptimeService: UptimeService,
    private val delayMillis: Long = 5000
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val familyRulesClient: FamilyRulesClient = FamilyRulesClient(context, settingsManager)

    companion object {
        fun install(
            context: Context,
            settingsManager: SettingsManager,
            uptimeService: UptimeService,
            delayMillis: Long = 5000
        ) {
            ReportService(context, settingsManager, uptimeService, delayMillis).start()
        }
    }

    fun start() {
        familyRulesClient.sendLaunchRequest()
        scope.launch {
            while (isActive) {
                if (ScreenStatus.isScreenOn(context)) {
                    performTask()
                }
                delay(delayMillis)
            }
        }
    }

    private fun performTask() {
        val uptime = uptimeService.getUptime()
        Log.i("ReportService", "Reporting: ${uptime.screenTimeMillis}")
        familyRulesClient.reportUptime(uptime)
    }

}