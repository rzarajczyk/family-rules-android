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

class PeriodicReportSender(
    private val context: Context,
    settingsManager: SettingsManager,
    private val periodicUptimeChecker: PeriodicUptimeChecker,
    private val delayDuration: Duration,
    private val appDb: AppDb
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val familyRulesClient: FamilyRulesClient = FamilyRulesClient(context, settingsManager, appDb)

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

    fun start() {
        scope.launch {
            familyRulesClient.sendClientInfoRequest()
        }
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