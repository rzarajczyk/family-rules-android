package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

interface PackageUsageChecker {
    fun checkUsageToday(usageManager: UsageStatsManager): List<PackageUsage>
}


class EventBasedPackageUsageChecker : PackageUsageChecker {
    enum class State { STARTING, STOPPING }
    data class ProcessedEvent(val state: State, val timestamp: Long) {
        fun debugString() = "ProcessedEvent(state=$state, timestamp=$timestamp [${
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        }])"
    }

    companion object {
        const val LOG_ALL_PACKAGES: Boolean = true
        const val DEBUG_PACKAGE: String = "pl.zarajczyk.familyrulesandroid"
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

    // inspiration: https://stackoverflow.com/questions/36238481/android-usagestatsmanager-not-returning-correct-daily-results/50647945#50647945
    override fun checkUsageToday(usageManager: UsageStatsManager): List<PackageUsage> {
        // Set the starting and ending times to be midnight in the system's default timezone
        val now = Instant.now()
        val startOfDay = now.atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).toInstant()
        val start = startOfDay.toEpochMilli()
        val end = now.toEpochMilli()

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
        val stats = mutableListOf<PackageUsage>()

        if (LOG_ALL_PACKAGES) {
            eventsPerPackage.keys.forEach {
                Log.d("PackageUsageChecker", "Package: $it")
            }
        }

        // Go through the events by package name
        eventsPerPackage.forEach { packageName, events ->
            var convertedEvents: List<ProcessedEvent> = events.convert()

            if (convertedEvents.isEmpty()) {
                return@forEach
            }

            if (packageName == DEBUG_PACKAGE) {
                convertedEvents.forEach {
                    Log.d("PackageUsageChecker", "Event: ${it.debugString()}")
                }
            }

            if (convertedEvents.first().state == State.STOPPING) {
                convertedEvents =
                    listOf(ProcessedEvent(State.STARTING, start)) + convertedEvents
                if (packageName == DEBUG_PACKAGE) {
                    Log.d(
                        "PackageUsageChecker",
                        "Prepending fake STARTING event: ${convertedEvents.first()}"
                    )
                }
            }

            if (convertedEvents.last().state == State.STARTING) {
                convertedEvents = convertedEvents + ProcessedEvent(State.STOPPING, now.toEpochMilli())
                if (packageName == DEBUG_PACKAGE) {
                    Log.d(
                        "PackageUsageChecker",
                        "Appending fake STOPPING event: ${convertedEvents.last()}"
                    )
                }
            }

            // group convertedEvents into pairs of STARTING and STOPPING
            val totalTime = convertedEvents
                .chunked(2)
                .sumOf {
                    if (packageName == DEBUG_PACKAGE) {
                        Log.d(
                            "PackageUsageChecker",
                            "Chunk: ${it.first().debugString()} - ${
                                it.last().debugString()
                            } // ${it.last().timestamp - it.first().timestamp}ms"
                        )
                    }
                    it.last().timestamp - it.first().timestamp
                }

            if (packageName == DEBUG_PACKAGE) {
                Log.d("PackageUsageChecker", "Total time: $totalTime")
            }

            stats.add(PackageUsage(packageName, totalTime))
        }
        return stats
    }
}