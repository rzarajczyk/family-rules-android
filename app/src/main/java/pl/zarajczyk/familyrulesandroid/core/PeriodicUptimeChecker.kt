package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration

class PeriodicUptimeChecker(private val context: Context, private val delayDuration: Duration) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var uptime: Uptime = Uptime(emptyList(), 0)

    private val uptimePreferences: SharedPreferences =
        context.getSharedPreferences("uptime_preferences", Context.MODE_PRIVATE)

    fun start() {
        uptime = performTask(delayDuration.inWholeMilliseconds)
        scope.launch {
            while (isActive) {
                if (ScreenStatus.isScreenOn(context)) {
                    uptime = performTask(delayDuration.inWholeMilliseconds)
                }
                delay(delayDuration)
            }
        }
    }

    fun getUptime(): Uptime {
        return uptime
    }

    private fun performTask(tick: Long): Uptime {
        val uptime = UptimeFetcher.fetchUptime(context, uptimePreferences, tick)
        Log.i("UptimeService", "Uptime: ${uptime.screenTimeMillis}")
        return uptime
    }
}

data class Uptime(
    val packageUsages: List<PackageUsage>,
    val screenTimeMillis: Long
)

data class PackageUsage(
    var packageName: String,
    var totalTimeInForegroundMillis: Long
)

private object UptimeFetcher {

    fun fetchUptime(
        applicationContext: Context,
        uptimePreferences: SharedPreferences,
        tick: Long
    ): Uptime {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val usageStatsList = fetchPackageUsage(applicationContext, uptimePreferences, tick, today)
        val screenTime = getTotalScreenOnTimeSinceMidnight(uptimePreferences, tick, today)
        return Uptime(usageStatsList, screenTime)
    }

//    private fun fetchPackageUsage(context: Context): List<PackageUsage> {
//        val usageStatsManager =
//            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
//        val endTime = System.currentTimeMillis()
//        val todayMidnight = Calendar.getInstance().apply {
//            set(Calendar.HOUR_OF_DAY, 0)
//            set(Calendar.MINUTE, 0)
//            set(Calendar.SECOND, 0)
//            set(Calendar.MILLISECOND, 0)
//        }
//        val startTime = todayMidnight.timeInMillis
//        val usageStatsList = usageStatsManager.queryUsageStats(INTERVAL_DAILY, startTime, endTime)
//
//        val usageStatsMap = mutableMapOf<String, Long>()
//
//        usageStatsList
//            .asSequence()
//            .filter { stat -> stat.totalTimeInForeground > 60 * 1000 }
//            .filter { stat -> !isSystemApp(context, stat.packageName) }
//            .forEach { stat ->
//                if (usageStatsMap.containsKey(stat.packageName)) {
//                    usageStatsMap[stat.packageName] =
//                        usageStatsMap[stat.packageName]!! + stat.totalTimeInForeground
//                } else {
//                    usageStatsMap[stat.packageName] = stat.totalTimeInForeground
//                }
//            }
//
//        return usageStatsMap.map { (packageName, totalTime) ->
//            PackageUsage(
//                packageName = packageName,
//                totalTimeInForegroundMillis = totalTime
//            )
//        }
//    }

    private fun fetchPackageUsage(
        context: Context,
        uptimePreferences: SharedPreferences,
        tick: Long,
        today: String
    ): List<PackageUsage> {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val packageUsageToday = uptimePreferences.all
            .filter { it.key.startsWith("package_usage_${today}_") }
            .mapKeys { (k,_) -> k.removePrefix("package_usage_${today}_") }
            .mapValues { (_,v) -> v as Long }
            .toMutableMap()

        val currentTime = System.currentTimeMillis()

        val usageStatsLastTick = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            currentTime - tick,
            currentTime
        )

        if (!usageStatsLastTick.isNullOrEmpty()) {
            val sortedStats = usageStatsLastTick.sortedByDescending { it.lastTimeUsed }
            val packageName = sortedStats.firstOrNull()?.packageName ?: "<unknown>"
            val packageUptime = (packageUsageToday[packageName] ?: 0) + tick
            packageUsageToday[packageName] = packageUptime

            uptimePreferences
                .edit()
                .putLong("package_usage_${today}_$packageName", packageUptime)
                .apply()
        }

        return packageUsageToday.map { (packageName, totalTime) ->
            PackageUsage(
                packageName = packageName,
                totalTimeInForegroundMillis = totalTime
            )
        }
    }

    private fun getTotalScreenOnTimeSinceMidnight(
        uptimePreferences: SharedPreferences,
        tick: Long,
        today: String
    ): Long {
        val screenTimeKey = "screen_time_$today"

        if (!uptimePreferences.contains(screenTimeKey)) {
            removeOldScreenTimeKeys(uptimePreferences)
        }

        var totalScreenOnTime = uptimePreferences.getLong(screenTimeKey, 0)
        totalScreenOnTime += tick

        uptimePreferences.edit().putLong(screenTimeKey, totalScreenOnTime).apply()


        return totalScreenOnTime
    }

    private fun removeOldScreenTimeKeys(uptimePreferences: SharedPreferences) {
        val oneWeekAgo = SimpleDateFormat(
            "yyyyMMdd",
            Locale.getDefault()
        ).format(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000))

        val editor = uptimePreferences.edit()
        try {
            uptimePreferences.all.keys.forEach { key ->
                if (key.startsWith("screen_time_") && key < "screen_time_$oneWeekAgo") {
                    editor.remove(key)
                }
                if (key.startsWith("package_usage_") && key < "package_usage_$oneWeekAgo") {
                    editor.remove(key)
                }
            }
        } finally {
            editor.apply()
        }
    }

//    private fun getTotalScreenOnTimeSinceMidnight(applicationContext: Context): Long {
//        val usageStatsManager =
//            applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
//        val endTime = System.currentTimeMillis()
//        val todayMidnight = Calendar.getInstance().apply {
//            set(Calendar.HOUR_OF_DAY, 0)
//            set(Calendar.MINUTE, 0)
//            set(Calendar.SECOND, 0)
//            set(Calendar.MILLISECOND, 0)
//        }
//        val startTime = todayMidnight.timeInMillis
//        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
//        var totalScreenOnTime = 0L
//        var screenOnTime = 0L
//        var screenOffTime: Long
//
//        while (usageEvents.hasNextEvent()) {
//            val event = UsageEvents.Event()
//            usageEvents.getNextEvent(event)
//            when (event.eventType) {
//                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOnTime = event.timeStamp
//                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
//                    screenOffTime = event.timeStamp
//                    if (screenOnTime != 0L) {
//                        totalScreenOnTime += screenOffTime - screenOnTime
//                        screenOnTime = 0L
//                    }
//                }
//            }
//        }
//        if (screenOnTime != 0L) {
//            totalScreenOnTime += endTime - screenOnTime
//        }
//        return totalScreenOnTime
//    }

}