package pl.zarajczyk.familyrulesandroid.domain

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

class UptimeService(private val context: Context, private val delayMillis: Long = 5000) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile
    private var uptime: Uptime = Uptime(emptyList(), 0)

    fun start(onFirstFetch: () -> Unit = {}) {
        scope.launch {
            while (isActive) {
                uptime = performTask()
                onFirstFetch()
                delay(delayMillis)
            }
        }
    }

    fun getUptime(): Uptime {
        return uptime
    }

    private fun performTask(): Uptime {
        val uptime = fetchUptime(context)
        Log.i("UptimeService", "Uptime: ${uptime.screenTimeSec}")
        return uptime
    }
}

data class Uptime(
    val usageStatistics: List<UsageStatistics>,
    val screenTimeSec: Long
)

fun fetchUptime(applicationContext: Context): Uptime {
    val usageStatsList = fetchUsageStats(applicationContext)
    val screenTime = getTotalScreenOnTimeSinceMidnight(applicationContext)
    return Uptime(usageStatsList, screenTime)
}

data class UsageStatistics(
    var packageName: String = "",
    var totalTimeInForeground: Long = 0
)

fun fetchUsageStats(applicationContext: Context): List<UsageStatistics> {
    val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val endTime = System.currentTimeMillis()
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startTime = calendar.timeInMillis
    val usageStatsList = usageStatsManager.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY,
        startTime,
        endTime
    )

    val usageStatsMap = mutableMapOf<String, Long>()

    usageStatsList.forEach { stat ->
        if (!isSystemApp(applicationContext, stat.packageName) && stat.totalTimeInForeground > 0) {
            if (usageStatsMap.containsKey(stat.packageName)) {
                usageStatsMap[stat.packageName] = usageStatsMap[stat.packageName]!! + stat.totalTimeInForeground
            } else {
                usageStatsMap[stat.packageName] = stat.totalTimeInForeground
            }
        }
    }

    return usageStatsMap.map { (packageName, totalTime) ->
        UsageStatistics().apply {
            this.packageName = packageName
            this.totalTimeInForeground = totalTime
        }
    }
}

private fun isSystemApp(applicationContext: Context, packageName: String): Boolean {
    return try {
        val packageManager = applicationContext.packageManager
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

fun getTotalScreenOnTimeSinceMidnight(applicationContext: Context): Long {
    val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val endTime = System.currentTimeMillis()
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startTime = calendar.timeInMillis
    val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
    var totalScreenOnTime = 0L
    var screenOnTime = 0L
    var screenOffTime = 0L

    while (usageEvents.hasNextEvent()) {
        val event = UsageEvents.Event()
        usageEvents.getNextEvent(event)
        when (event.eventType) {
            UsageEvents.Event.SCREEN_INTERACTIVE -> screenOnTime = event.timeStamp
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                screenOffTime = event.timeStamp
                if (screenOnTime != 0L) {
                    totalScreenOnTime += screenOffTime - screenOnTime
                    screenOnTime = 0L
                }
            }
        }
    }
    if (screenOnTime != 0L) {
        totalScreenOnTime += endTime - screenOnTime
    }
    return totalScreenOnTime / 1000 // Convert to seconds
}