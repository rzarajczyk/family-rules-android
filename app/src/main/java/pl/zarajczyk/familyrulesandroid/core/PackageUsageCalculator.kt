package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents

class PackageUsageCalculator : SystemEventProcessor {
    fun getTodayPackageUsage(): Map<String, Long> {
        return todayPackageUsage
    }

    @Volatile
    private var todayPackageUsage = mutableMapOf<String, Long>()

    private enum class State { STARTING, STOPPING }
    private data class PackageLifecycleEvent(val state: State, val timestamp: Long)

    override fun onMidnight() {
        todayPackageUsage = mutableMapOf()
    }

    override fun onEventBatch(events: List<UsageEvents.Event>, start: Long, end: Long) {
        val eventsPerPackage = events.groupBy { it.packageName }

        eventsPerPackage.forEach { (packageName, events) ->
            var packageLifecycleEvents = events.toPackageLifecycleEvents()

            if (packageLifecycleEvents.isEmpty()) {
                return@forEach
            }

            if (packageLifecycleEvents.first().state == State.STOPPING) {
                packageLifecycleEvents =
                    listOf(PackageLifecycleEvent(State.STARTING, start)) + packageLifecycleEvents
            }

            if (packageLifecycleEvents.last().state == State.STARTING) {
                packageLifecycleEvents =
                    packageLifecycleEvents + PackageLifecycleEvent(State.STOPPING, end)
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

    private fun List<UsageEvents.Event>.toPackageLifecycleEvents(): List<PackageLifecycleEvent> =
        this
            .mapNotNull {
                when (it.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> PackageLifecycleEvent(
                        State.STARTING,
                        it.timeStamp
                    )

                    UsageEvents.Event.ACTIVITY_PAUSED -> PackageLifecycleEvent(
                        State.STOPPING,
                        it.timeStamp
                    )

                    UsageEvents.Event.ACTIVITY_STOPPED -> PackageLifecycleEvent(
                        State.STOPPING,
                        it.timeStamp
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