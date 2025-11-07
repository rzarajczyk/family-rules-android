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
        return foregroundApp
    }

    @Volatile
    private var todayPackageUsage = mutableMapOf<String, Long>()

    @Volatile
    private var foregroundApp: String? = null


    private enum class State { STARTING, STOPPING }
    private data class PackageLifecycleEvent(
        val state: State,
        val packageName: String,
        val timestamp: Long
    )

    override fun reset() {
        todayPackageUsage = mutableMapOf()
    }

    private fun MutableMap<String, Long>.increment(key: String, value: Long) {
        this[key] = this[key]?.let { it + value } ?: value
    }

    override fun processEventBatch(events: List<Event>, start: Long, end: Long) {
        val packageLifecycleEvents = events.toPackageLifecycleEvents()

        if (packageLifecycleEvents.isEmpty()) {
            foregroundApp?.let { fg ->
                todayPackageUsage.increment(fg, end - start)
//                Log.d("PackageUsageCalculator",  "Incrementing time (no events) for foreground package $fg by ${end - start}")
            }
            return
        }

        val lastStartingEvent = packageLifecycleEvents.lastOrNull { it.state == State.STARTING }
        if (lastStartingEvent != null) {
            val correspondingStoppedEventExists = packageLifecycleEvents.any {
                it.state == State.STOPPING && it.packageName == lastStartingEvent.packageName && it.timestamp > lastStartingEvent.timestamp
            }
            foregroundApp = if (correspondingStoppedEventExists) null else lastStartingEvent.packageName
        }

        val groupedPackageLifecycleEvents = packageLifecycleEvents
            .groupBy { it.packageName }

        groupedPackageLifecycleEvents
            .forEach { (packageName, events) ->
                val packageLifecycleEventsPerPackage = LinkedList(events.deduplicate())

                if (packageLifecycleEventsPerPackage.first().state == State.STOPPING) {
                    packageLifecycleEventsPerPackage.add(
                        index = 0,
                        PackageLifecycleEvent(State.STARTING, packageName, start)
                    )
//                    if (packageName == "com.android.settings")
//                        Log.i("PackageUsageCalculator", "Adding initial starting event for package: $packageName")
                }

                if (packageLifecycleEventsPerPackage.last().state == State.STARTING) {
                    packageLifecycleEventsPerPackage.add(
                        PackageLifecycleEvent(State.STOPPING, packageName, end)
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

        foregroundApp?.let { fg ->
            if (fg !in groupedPackageLifecycleEvents.keys) {
                todayPackageUsage.increment(fg, end - start)
//                Log.d("PackageUsageCalculator",  "Incrementing time (despite having events!) for foreground package $fg by ${end - start} // $packageLifecycleEvents")
            }
        }
    }

    private fun List<Event>.toPackageLifecycleEvents(): List<PackageLifecycleEvent> =
        this
            .mapNotNull {
                when (it.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> PackageLifecycleEvent(
                        State.STARTING,
                        it.packageName,
                        it.timestamp
                    )

                    UsageEvents.Event.ACTIVITY_PAUSED -> PackageLifecycleEvent(
                        State.STOPPING,
                        it.packageName,
                        it.timestamp
                    )

                    UsageEvents.Event.ACTIVITY_STOPPED -> PackageLifecycleEvent(
                        State.STOPPING,
                        it.packageName,
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
                } else {
                    acc[acc.lastIndex] = event
                }
                acc
            }
}