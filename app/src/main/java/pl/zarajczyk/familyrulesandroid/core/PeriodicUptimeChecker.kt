package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.usage.UsageStatsManager.INTERVAL_DAILY
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
    private var cachedSystemApps: MutableSet<String> = mutableSetOf()
    private var lastCacheUpdate: Long = 0L
    
    // Reusable objects to avoid allocations in hot path
    private val reusableEvent = UsageEvents.Event()
    private val calendar = Calendar.getInstance()

    fun fetchUptime(applicationContext: Context): Uptime {
        val usageStatsManager = getUsageStatsManager(applicationContext)
        val todayMidnight = getTodayMidnight()
        
        // Use single query for both screen time and app usage - much more efficient
        val usageEvents = usageStatsManager.queryEvents(todayMidnight, System.currentTimeMillis())
        val (packageUsages, screenTime) = processUsageEvents(usageEvents, applicationContext)
        
        return Uptime(packageUsages, screenTime)
    }

    private fun getUsageStatsManager(context: Context): UsageStatsManager {
        return cachedUsageStatsManager ?: run {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            cachedUsageStatsManager = manager
            manager
        }
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

    private fun processUsageEvents(
        usageEvents: UsageEvents, 
        context: Context
    ): Pair<List<PackageUsage>, Long> {
        val packageUsageMap = mutableMapOf<String, Long>()
        var totalScreenOnTime = 0L
        var screenOnTime = 0L
        val endTime = System.currentTimeMillis()
        
        // Track app foreground sessions for more accurate usage calculation
        val appForegroundSessions = mutableMapOf<String, Long>()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(reusableEvent)
            
            when (reusableEvent.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    screenOnTime = reusableEvent.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (screenOnTime != 0L) {
                        totalScreenOnTime += reusableEvent.timeStamp - screenOnTime
                        screenOnTime = 0L
                    }
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val packageName = reusableEvent.packageName
                    if (!isSystemAppCached(context, packageName)) {
                        appForegroundSessions[packageName] = reusableEvent.timeStamp
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val packageName = reusableEvent.packageName
                    if (!isSystemAppCached(context, packageName)) {
                        val foregroundStart = appForegroundSessions[packageName]
                        if (foregroundStart != null) {
                            val sessionTime = reusableEvent.timeStamp - foregroundStart
                            if (sessionTime > 60 * 1000) { // Only count sessions > 1 minute
                                packageUsageMap[packageName] = 
                                    (packageUsageMap[packageName] ?: 0L) + sessionTime
                            }
                            appForegroundSessions.remove(packageName)
                        }
                    }
                }
            }
        }

        // Handle case where screen is still on
        if (screenOnTime != 0L) {
            totalScreenOnTime += endTime - screenOnTime
        }
        
        // Handle any remaining foreground sessions (app still in foreground)
        appForegroundSessions.forEach { (packageName, startTime) ->
            val sessionTime = endTime - startTime
            if (sessionTime > 60 * 1000) {
                packageUsageMap[packageName] = 
                    (packageUsageMap[packageName] ?: 0L) + sessionTime
            }
        }

        val packageUsages = packageUsageMap.map { (packageName, totalTime) ->
            PackageUsage(packageName, totalTime)
        }

        return Pair(packageUsages, totalScreenOnTime)
    }

    private fun isSystemAppCached(context: Context, packageName: String): Boolean {
        if (cachedSystemApps.contains(packageName)) return true
        
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) {
                cachedSystemApps.add(packageName)
            }
            isSystem
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}