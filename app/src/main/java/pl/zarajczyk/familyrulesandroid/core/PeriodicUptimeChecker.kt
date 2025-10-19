package pl.zarajczyk.familyrulesandroid.core

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
import kotlin.time.Duration

class PeriodicUptimeChecker(private val context: Context, private val delayDuration: Duration) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var uptime: Uptime = Uptime(emptyList(), 0)

    fun start() {
        uptime = performTask()
        scope.launch {
            while (isActive) {
                if (ScreenStatus.isScreenOn(context)) {
                    uptime = performTask()
                }
                delay(delayDuration)
            }
        }
    }

    fun getUptime(): Uptime {
        return uptime
    }

    private fun performTask(): Uptime {
        val uptime = UptimeFetcher.fetchUptime(context)
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
    // Cache expensive objects to avoid repeated allocations
    private var cachedUsageStatsManager: UsageStatsManager? = null
    private var cachedTodayMidnight: Long = 0L
    private var lastCacheUpdate: Long = 0L
    
    // Reusable calendar to avoid allocations
    private val calendar = Calendar.getInstance()

    fun fetchUptime(applicationContext: Context): Uptime {
        val usageStatsManager = getUsageStatsManager(applicationContext)
        val todayMidnight = getTodayMidnight()
        val endTime = System.currentTimeMillis()
        
        // Get app usage data using the simpler and more reliable queryUsageStats
        val packageUsages = fetchPackageUsage(usageStatsManager, todayMidnight, endTime)
        
        // Get screen time using queryEvents for accuracy
        val screenTime = getScreenTime(usageStatsManager, todayMidnight, endTime)
        
        return Uptime(packageUsages, screenTime)
    }

    private fun getUsageStatsManager(context: Context): UsageStatsManager {
        if (cachedUsageStatsManager == null) {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            cachedUsageStatsManager = manager
        }
        return cachedUsageStatsManager ?: throw IllegalStateException("UsageStatsManager is null")
    }

    private fun getTodayMidnight(): Long {
        val now = System.currentTimeMillis()
        // Cache for 1 hour to avoid recalculation - midnight only changes once per day
        if (now - lastCacheUpdate > 3600000) {
            calendar.timeInMillis = now
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            cachedTodayMidnight = calendar.timeInMillis
            lastCacheUpdate = now
        }
        return cachedTodayMidnight
    }

    private fun fetchPackageUsage(
        usageStatsManager: UsageStatsManager,
        startTime: Long,
        endTime: Long
    ): List<PackageUsage> {
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, 
            startTime, 
            endTime
        )

        return usageStatsList
            .asSequence()
            .filter { stat -> stat.totalTimeInForeground > 60 * 1000 } // Only apps used > 1 minute
            .map { stat ->
                PackageUsage(
                    packageName = stat.packageName,
                    totalTimeInForegroundMillis = stat.totalTimeInForeground
                )
            }
            .toList()
    }

    private fun getScreenTime(
        usageStatsManager: UsageStatsManager,
        startTime: Long,
        endTime: Long
    ): Long {
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        var totalScreenOnTime = 0L
        var screenOnTime = 0L

        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    screenOnTime = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (screenOnTime != 0L) {
                        totalScreenOnTime += event.timeStamp - screenOnTime
                        screenOnTime = 0L
                    }
                }
            }
        }

        // Handle case where screen is still on
        if (screenOnTime != 0L) {
            totalScreenOnTime += endTime - screenOnTime
        }

        return totalScreenOnTime
    }
}