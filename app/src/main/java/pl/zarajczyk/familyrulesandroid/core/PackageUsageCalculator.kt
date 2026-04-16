package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import android.util.Log
import java.time.Instant
import java.util.LinkedList

class PackageUsageCalculator : SystemEventProcessor {
    fun getTodayPackageUsage(): Map<String, Long> {
        return todayPackageUsage
    }

    fun getForegroundApp(): String? {
        return foregroundActivity?.packageName
    }

    @Volatile
    private var todayPackageUsage = mutableMapOf<String, Long>()

    @Volatile
    private var foregroundActivity: ActivityId? = null


    private enum class State { STARTING, STOPPING }
    private data class PackageLifecycleEvent(
        val state: State,
        val packageName: String,
        val className: String,
        val timestamp: Long
    )

    private data class ActivityId(val packageName: String, val className: String)

    override fun reset() {
        todayPackageUsage = mutableMapOf()
    }

    private fun MutableMap<String, Long>.increment(key: String, value: Long) {
        this[key] = this[key]?.let { it + value } ?: value
    }

    override fun processEventBatch(events: List<Event>, start: Long, end: Long) {
        val packageLifecycleEvents = events.toPackageLifecycleEvents()

        events.forEach { e ->
            val typeName = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
                UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
                UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
                UsageEvents.Event.FOREGROUND_SERVICE_START -> "FOREGROUND_SERVICE_START"
                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "FOREGROUND_SERVICE_STOP"
                UsageEvents.Event.CONFIGURATION_CHANGE -> "CONFIGURATION_CHANGE"
                UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
                UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
                UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
                UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
                12 -> "NOTIFICATION_INTERRUPTION"
                else -> "UNKNOWN(${e.eventType})"
            }
            Log.d("PackageUsageCalculator", "RawEvent: $typeName ${e.packageName}/${e.className} at ${Instant.ofEpochMilli(e.timestamp)}")
        }

        if (packageLifecycleEvents.isEmpty()) {
            foregroundActivity?.let { fg ->
                Log.d("PackageUsageCalculator", "Foreground app is still: $fg")
                todayPackageUsage.increment(fg.packageName, end - start)
            } ?: Log.d("PackageUsageCalculator", "No foreground app on early return")
            return
        }

        val lastStartingEvent = packageLifecycleEvents.lastOrNull { it.state == State.STARTING }
        if (lastStartingEvent != null) {
            val correspondingStoppedEventExists = packageLifecycleEvents.any {
                it.state == State.STOPPING
                    && it.packageName == lastStartingEvent.packageName
                    && it.className == lastStartingEvent.className
                    && it.timestamp > lastStartingEvent.timestamp
            }
            foregroundActivity = if (correspondingStoppedEventExists) null
                                  else ActivityId(lastStartingEvent.packageName, lastStartingEvent.className)
        } else if (packageLifecycleEvents.any {
                it.state == State.STOPPING
                    && it.packageName == foregroundActivity?.packageName
                    && it.className == foregroundActivity?.className
            }) {
            foregroundActivity = null
        }

        val groupedPackageLifecycleEvents = packageLifecycleEvents
            .groupBy { it.packageName }

        groupedPackageLifecycleEvents
            .forEach { (packageName, events) ->
                val packageLifecycleEventsPerPackage = LinkedList(events.deduplicate())

                if (packageLifecycleEventsPerPackage.first().state == State.STOPPING) {
                    packageLifecycleEventsPerPackage.add(
                        index = 0,
                        PackageLifecycleEvent(State.STARTING, packageName, "", start)
                    )
//                    if (packageName == "com.android.settings")
//                        Log.i("PackageUsageCalculator", "Adding initial starting event for package: $packageName")
                }

                if (packageLifecycleEventsPerPackage.last().state == State.STARTING) {
                    packageLifecycleEventsPerPackage.add(
                        PackageLifecycleEvent(State.STOPPING, packageName, "", end)
                    )
//                    if (packageName == "com.android.settings")
//                        Log.i("PackageUsageCalculator", "Adding final stopping event for package: $packageName")
                }

                val totalTime = packageLifecycleEventsPerPackage
                    .chunked(2)
                    .sumOf {
                        val sum = it.last().timestamp - it.first().timestamp
//                        if (packageName == "com.android.settings") {
//                            Log.i(
//                                "PackageUsageCalculator",
//                                " - adding time for package $packageName: $sum (${Instant.ofEpochMilli(it.first().timestamp)} - ${Instant.ofEpochMilli(it.last().timestamp)})"
//                            )
//                        }
                        sum
                    }

//                Log.i("PackageUsageCalculator", "Total time for package $packageName: $totalTime")

                todayPackageUsage.increment(packageName, totalTime)
//                Log.d("PackageUsageCalculator", "Total time for package $packageName: ${todayPackageUsage[packageName]}")
            }

        foregroundActivity?.packageName?.let { fg ->
            Log.d("PackageUsageCalculator", "Foreground app: $fg")
            if (fg !in groupedPackageLifecycleEvents.keys) {
                todayPackageUsage.increment(fg, end - start)
            }
        } ?: Log.d("PackageUsageCalculator", "No foreground app")
    }

    private fun List<Event>.toPackageLifecycleEvents(): List<PackageLifecycleEvent> =
        this
            .mapNotNull {
                when (it.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> PackageLifecycleEvent(
                        State.STARTING,
                        it.packageName,
                        it.className,
                        it.timestamp
                    )

                    UsageEvents.Event.ACTIVITY_PAUSED -> PackageLifecycleEvent(
                        State.STOPPING,
                        it.packageName,
                        it.className,
                        it.timestamp
                    )

                    UsageEvents.Event.ACTIVITY_STOPPED -> PackageLifecycleEvent(
                        State.STOPPING,
                        it.packageName,
                        it.className,
                        it.timestamp
                    )

                    UsageEvents.Event.FOREGROUND_SERVICE_START -> PackageLifecycleEvent(
                        State.STARTING,
                        it.packageName,
                        it.className,
                        it.timestamp
                    )

                    UsageEvents.Event.FOREGROUND_SERVICE_STOP -> PackageLifecycleEvent(
                        State.STOPPING,
                        it.packageName,
                        it.className,
                        it.timestamp
                    )

                    else -> null
                }
            }

    private fun List<PackageLifecycleEvent>.deduplicate() =
        this
            .fold(mutableListOf<PackageLifecycleEvent>()) { acc, event ->
                if (acc.isEmpty() || acc.last().state != event.state) {
                    acc.add(event)
                } else if (event.state == State.STARTING) {
                    // For STARTING: keep last (most recent resume)
                    acc[acc.lastIndex] = event
                }
                // For STOPPING: keep first (PAUSED = real end of usage; STOPPED is a delayed system event)
                acc
            }
}