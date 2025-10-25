package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.TimeZone
import kotlin.time.Duration

class PeriodicUptimeChecker(private val context: Context, private val delayDuration: Duration) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var uptime: Uptime = Uptime(emptyList(), 0)

    fun start() {
        // Perform initial calculation immediately
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

    fun forceUpdate(): Uptime {
        uptime = performTask()
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
        Log.d("UptimeFetcher", "Took: ${System.currentTimeMillis() - endTime}ms")

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
            // Use UTC timezone to match UsageStatsManager's data storage
            calendar.timeZone = TimeZone.getTimeZone("UTC")
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
        return StackOverflowVersionOfUptimeChecker
            .getDailyStats(usageStatsManager)
            .map { PackageUsage(it.packageName, it.totalTime) }
            .sortedByDescending { it.totalTimeInForegroundMillis }
            .filter { it.totalTimeInForegroundMillis > 60 * 1000 }


        ///
//        val usageStatsList = usageStatsManager.queryUsageStats(
//            UsageStatsManager.INTERVAL_DAILY,
//            startTime,
//            endTime
//        )

        // Debug: Log all usage stats to understand duplicates
//        Log.d("UptimeFetcher", "Raw usage stats count: ${usageStatsList.size}")
//        usageStatsList.forEach { stat ->
//            Log.d("UptimeFetcher", "Package: ${stat.packageName}, Time: ${stat.totalTimeInForeground}, LastTimeUsed: ${stat.lastTimeUsed}")
//        }

//        val filteredStats = usageStatsList
//            .asSequence()
//            .filter { stat ->
//                stat.totalTimeInForeground > 60 * 1000 && // Only apps used > 1 minute
//                stat.lastTimeUsed >= startTime // Only apps used today
//            }
//            .map { stat ->
//                PackageUsage(
//                    packageName = stat.packageName,
//                    totalTimeInForegroundMillis = stat.totalTimeInForeground
//                )
//            }
//            .toList()

        // Debug: Log filtered results and check for duplicates
//        Log.d("UptimeFetcher", "Filtered package usage count: ${filteredStats.size}")
//        val packageNames = filteredStats.map { it.packageName }
//        val uniquePackageNames = packageNames.toSet()
//        if (packageNames.size != uniquePackageNames.size) {
//            Log.w("UptimeFetcher", "Found duplicate packages! Total: ${packageNames.size}, Unique: ${uniquePackageNames.size}")
//            val duplicates = packageNames.groupingBy { it }.eachCount().filter { it.value > 1 }
//            Log.w("UptimeFetcher", "Duplicate packages: $duplicates")
//        }

        // Deduplicate packages by name and take maximum usage time
//        val deduplicatedStats = filteredStats
//            .groupBy { it.packageName }
//            .map { (packageName, packageUsages) ->
//                val totalTime = packageUsages.maxOf { it.totalTimeInForegroundMillis }
//
//                // Debug: Log when multiple entries exist for the same package
//                if (packageUsages.size > 1) {
//                    Log.d("UptimeFetcher", "Multiple entries for $packageName: ${packageUsages.size} entries")
//                    packageUsages.forEachIndexed { index, usage ->
//                        Log.d("UptimeFetcher", "  Entry $index: ${usage.totalTimeInForegroundMillis}ms")
//                    }
//                    Log.d("UptimeFetcher", "  Using maxOf: ${totalTime}ms")
//                }
//
//                PackageUsage(
//                    packageName = packageName,
//                    totalTimeInForegroundMillis = totalTime
//                )
//            }
//            .sortedByDescending { it.totalTimeInForegroundMillis }
//
//        Log.d("UptimeFetcher", "Deduplicated package usage count: ${deduplicatedStats.size}")
//
//        return deduplicatedStats
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

object StackOverflowVersionOfUptimeChecker {
    enum class State { STARTING, STOPPING }
    data class ProcessedEvent(val state: State, val timestamp: Long)

    private fun List<UsageEvents.Event>.convert() = this
        .mapNotNull {
            when (it.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ProcessedEvent(State.STARTING, it.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED -> ProcessedEvent(State.STOPPING, it.timeStamp)
                UsageEvents.Event.ACTIVITY_STOPPED -> ProcessedEvent(State.STOPPING, it.timeStamp)
                else -> null
            }
        }
        .fold(mutableListOf<ProcessedEvent>()) { acc, event ->
            if (acc.isEmpty() || acc.last().state != event.state) {
                acc.add(event)
            } else {
                acc[acc.lastIndex] = event
            }
            acc
        }

    // source: https://stackoverflow.com/questions/36238481/android-usagestatsmanager-not-returning-correct-daily-results/50647945#50647945
    fun getDailyStats(usageManager: UsageStatsManager): List<Stat> {
        // The timezones we'll need
        val date = LocalDate.now()
        val utc = ZoneId.of("UTC")
        val defaultZone = ZoneId.systemDefault()

        // Set the starting and ending times to be midnight in UTC time
        val startDate = date.atStartOfDay(defaultZone).withZoneSameInstant(utc)
        val start = startDate.toInstant().toEpochMilli()
        val end = startDate.plusDays(1).toInstant().toEpochMilli()

        // This will keep a map of all of the events per package name
        val eventsPerPackage = mutableMapOf<String, MutableList<UsageEvents.Event>>()

        // Query the list of events that has happened within that time frame
        val systemEvents = usageManager.queryEvents(start, end)
        while (systemEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            systemEvents.getNextEvent(event)

            // Get the list of events for the package name, create one if it doesn't exist
            val packageEvents = eventsPerPackage[event.packageName] ?: mutableListOf()
            packageEvents.add(event)
            eventsPerPackage[event.packageName] = packageEvents
        }

        // This will keep a list of our final stats
        val stats = mutableListOf<Stat>()

        // Go through the events by package name
        eventsPerPackage.forEach { packageName, events ->
            // Keep track of the current start and end times
            var startTime = 0L
            var endTime = 0L
            // Keep track of the total usage time for this app
            var totalTime = 0L
            // Keep track of the start times for this app
//            val startTimes = mutableListOf<ZonedDateTime>()

            if (packageName == "com.ingka.ikea.app") {
                Log.i("UptimeChecker", "START")
            }

            events.convert().forEach {
                if (it.state == State.STARTING) {
                    // App was moved to the foreground: set the start time
                    startTime = it.timestamp
                    // Add the start time within this timezone to the list
//                    startTimes.add(
//                        Instant.ofEpochMilli(startTime).atZone(utc)
//                        .withZoneSameInstant(defaultZone))
                } else {
                    endTime = it.timestamp
                }

                // If there's an end time with no start time, this might mean that
                //  The app was started on the previous day, so take midnight
                //  As the start time
                if (startTime == 0L && endTime != 0L) {
                    startTime = start
                }

                // If both start and end are defined, we have a session
                if (startTime != 0L && endTime != 0L) {
                    // Add the session time to the total time
                    totalTime += endTime - startTime
                    // Reset the start/end times to 0
                    startTime = 0L
                    endTime = 0L
                }
            }

            // If there is a start time without an end time, this might mean that
            //  the app was used past midnight, so take (midnight - 1 second)
            //  as the end time
            if (startTime != 0L && endTime == 0L) {
                totalTime += end - 1000 - startTime
            }
            stats.add(Stat(packageName, totalTime, /*startTimes*/emptyList<ZonedDateTime>()))
        }
        return stats
    }

    // Helper class to keep track of all of the stats
    class Stat(val packageName: String, val totalTime: Long, val startTimes: List<ZonedDateTime>)
}