package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

interface PackageUsageChecker {
    fun checkUsageToday(usageManager: UsageStatsManager): List<PackageUsage>
}


class EventBasedPackageUsageChecker : PackageUsageChecker {
    enum class State { STARTING, STOPPING }
    data class ProcessedEvent(val state: State, val timestamp: Long) {
        fun debugString() = "ProcessedEvent(state=$state, timestamp=$timestamp [${Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())}])"
    }

    companion object {
        const val LOG_ALL_PACKAGES: Boolean = true
        const val DEBUG_PACKAGE: String = ""
    }

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
    override fun checkUsageToday(usageManager: UsageStatsManager): List<PackageUsage> {
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

        if (LOG_ALL_PACKAGES) {
            eventsPerPackage.forEach {
                Log.d("PackageUsageChecker", "Package: $it")
            }
        }

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
        val stats = mutableListOf<PackageUsage>()

        // Go through the events by package name
        eventsPerPackage.forEach { packageName, events ->
            // Keep track of the current start and end times
            var startTime = 0L
            var endTime = 0L
            // Keep track of the total usage time for this app
            var totalTime = 0L
            // Keep track of the start times for this app
//            val startTimes = mutableListOf<ZonedDateTime>()

            val convertedEvents = events.convert()

            if (packageName == DEBUG_PACKAGE) {
                convertedEvents.forEach {
                    Log.d("PackageUsageChecker", "Event: ${it.debugString()}")
                }
            }

            convertedEvents.forEach {
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
            stats.add(PackageUsage(packageName, totalTime))
        }
        return stats
    }
}