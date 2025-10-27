package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import android.util.Log

class ForegroundAppCalculator : SystemEventProcessor {
    fun getForegroundApp(): String? {
        return foregroundApp
    }

    @Volatile
    private var foregroundApp: String? = null

    private enum class State { STARTING, STOPPING }
    private data class PackageLifecycleEvent(val state: State, val packageName: String, val timestamp: Long)

    override fun onMidnight() {
    }

    override fun onEventBatch(events: List<UsageEvents.Event>, start: Long, end: Long) {
        val packageLifecycleEvents = events.toPackageLifecycleEvents()

        if (packageLifecycleEvents.isEmpty()) {
            return
        }

        val lastStartingEvent = packageLifecycleEvents.lastOrNull { it.state == State.STARTING }

        if (lastStartingEvent == null) {
            return
        }

        val correspondingStoppedEventExists = packageLifecycleEvents.any {
            it.state == State.STOPPING && it.packageName == lastStartingEvent.packageName && it.timestamp > lastStartingEvent.timestamp
        }

        foregroundApp = if (correspondingStoppedEventExists) null else lastStartingEvent.packageName
        Log.d("ForegroundAppCalculator", "Foreground app: $foregroundApp")
    }

    private fun List<UsageEvents.Event>.toPackageLifecycleEvents(): List<PackageLifecycleEvent> =
        this
            .mapNotNull {
                when (it.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> PackageLifecycleEvent(
                        State.STARTING,
                        it.packageName,
                        it.timeStamp
                    )

                    UsageEvents.Event.ACTIVITY_PAUSED -> PackageLifecycleEvent(
                        State.STOPPING,
                        it.packageName,
                        it.timeStamp
                    )

                    UsageEvents.Event.ACTIVITY_STOPPED -> PackageLifecycleEvent(
                        State.STOPPING,
                        it.packageName,
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