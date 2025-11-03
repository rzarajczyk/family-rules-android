package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import java.util.LinkedList

class PackageUsageCalculator : SystemEventProcessor {
    fun getTodayPackageUsage(): Map<String, Long> {
        return todayPackageUsage
    }

    @Volatile
    private var todayPackageUsage = mutableMapOf<String, Long>()

    private enum class State { STARTING, STOPPING }
    private data class PackageLifecycleEvent(val state: State, val packageName: String, val timestamp: Long)

    override fun reset() {
        todayPackageUsage = mutableMapOf()
    }

    override fun processEventBatch(events: List<Event>, start: Long, end: Long) {
        val eventsPerPackage = events.groupBy { it.packageName }

        eventsPerPackage.forEach { (packageName, events) ->
            val packageLifecycleEvents = LinkedList(events.toPackageLifecycleEvents())

            if (packageLifecycleEvents.isEmpty()) {
                return@forEach
            }

            if (packageLifecycleEvents.first().state == State.STOPPING) {
                packageLifecycleEvents.add(
                    index = 0,
                    PackageLifecycleEvent(State.STARTING, packageName, start)
                )
            }

            if (packageLifecycleEvents.last().state == State.STARTING) {
                packageLifecycleEvents.add(
                    PackageLifecycleEvent(State.STOPPING, packageName, end)
                )
            }

            val totalTime = packageLifecycleEvents
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
            .fold(mutableListOf<PackageLifecycleEvent>()) { acc, event ->
                if (acc.isEmpty() || acc.last().state != event.state) {
                    acc.add(event)
                } else {
                    acc[acc.lastIndex] = event
                }
                acc
            }
}