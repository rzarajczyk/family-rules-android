package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
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

    override fun processEventBatch(events: List<Event>, start: Long, end: Long) {
        val packageLifecycleEvents = events.toPackageLifecycleEvents()

        if (packageLifecycleEvents.isEmpty()) {
            return
        }

        val lastStartingEvent = packageLifecycleEvents.lastOrNull { it.state == State.STARTING }
        if (lastStartingEvent != null) {
            val correspondingStoppedEventExists = packageLifecycleEvents.any {
                it.state == State.STOPPING && it.packageName == lastStartingEvent.packageName && it.timestamp > lastStartingEvent.timestamp
            }
            foregroundApp = if (correspondingStoppedEventExists) null else lastStartingEvent.packageName
        }

        packageLifecycleEvents
            .groupBy<PackageLifecycleEvent, String> { it.packageName }
            .forEach { (packageName, events) ->
                val packageLifecycleEventsPerPackage = LinkedList(events.deduplicate())

                if (packageLifecycleEventsPerPackage.first().state == State.STOPPING) {
                    packageLifecycleEventsPerPackage.add(
                        index = 0,
                        PackageLifecycleEvent(State.STARTING, packageName, start)
                    )
                }

                if (packageLifecycleEventsPerPackage.last().state == State.STARTING) {
                    packageLifecycleEventsPerPackage.add(
                        PackageLifecycleEvent(State.STOPPING, packageName, end)
                    )
                }

                val totalTime = packageLifecycleEventsPerPackage
                    .chunked(2)
                    .sumOf {
                        it.last().timestamp - it.first().timestamp
                    }

                todayPackageUsage[packageName] =
                    todayPackageUsage[packageName]?.let { it + totalTime }
                        ?: totalTime
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