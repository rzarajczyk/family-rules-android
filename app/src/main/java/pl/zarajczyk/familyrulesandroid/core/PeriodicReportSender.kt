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
import kotlin.time.Duration

class PeriodicReportSender(
    private val context: Context,
    settingsManager: SettingsManager,
    private val periodicUptimeChecker: PeriodicUptimeChecker,
    private val delayDuration: Duration
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val familyRulesClient: FamilyRulesClient = FamilyRulesClient(context, settingsManager)

    companion object {
        fun install(
            context: Context,
            settingsManager: SettingsManager,
            periodicUptimeChecker: PeriodicUptimeChecker,
            delayMillis: Duration
        ) {
            PeriodicReportSender(context, settingsManager, periodicUptimeChecker, delayMillis).start()
        }
    }

    fun start() {
        familyRulesClient.sendLaunchRequest()
        scope.launch {
            while (isActive) {
                if (ScreenStatus.isScreenOn(context)) {
                    performTask()
                }
                delay(delayDuration)
            }
        }
    }

    private fun performTask() {
        val uptime = periodicUptimeChecker.getUptime()
        Log.i("ReportService", "Reporting: ${uptime.screenTimeMillis}")
        familyRulesClient.reportUptime(uptime)
    }

}