package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import android.util.Log

/**
 * Tracks the *current* foreground activity by consuming usage events.
 *
 * This used to also accumulate per-package daily totals, but that accumulator
 * lost time whenever the event stream was replayed in bulk (screen-off triggers
 * `PeriodicUsageEventsMonitor.reset()` which re-feeds every event since
 * midnight). Daily totals are now sourced from `PackageUsageStatsProvider`,
 * which delegates to `UsageStatsManager.queryAndAggregateUsageStats(...)`.
 *
 * The only remaining responsibility is sub-second foreground detection used
 * by [ForegroundAppMonitor] to decide whether to show the blocking overlay.
 */
class PackageUsageCalculator : SystemEventProcessor {

    fun getForegroundApp(): String? = foregroundActivity?.packageName

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

    /**
     * Foreground tracking is derived freshly on every batch, so [reset] is a
     * no-op. We deliberately do *not* clear `foregroundActivity` on reset:
     * a screen-off does not change which app was last in the foreground, and
     * the next event batch will overwrite it correctly anyway.
     */
    override fun reset() {
        // no-op
    }

    override fun processEventBatch(events: List<Event>, start: Long, end: Long) {
        val packageLifecycleEvents = events.toPackageLifecycleEvents()

        if (packageLifecycleEvents.isEmpty()) {
            foregroundActivity?.let { fg ->
                Log.d("PackageUsageCalculator", "Foreground app is still: $fg")
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

        foregroundActivity?.let {
            Log.d("PackageUsageCalculator", "Foreground app: ${it.packageName}")
        } ?: Log.d("PackageUsageCalculator", "No foreground app")
    }

    private fun List<Event>.toPackageLifecycleEvents(): List<PackageLifecycleEvent> =
        this.mapNotNull {
            when (it.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> PackageLifecycleEvent(
                    State.STARTING, it.packageName, it.className, it.timestamp
                )

                UsageEvents.Event.ACTIVITY_PAUSED -> PackageLifecycleEvent(
                    State.STOPPING, it.packageName, it.className, it.timestamp
                )

                UsageEvents.Event.ACTIVITY_STOPPED -> PackageLifecycleEvent(
                    State.STOPPING, it.packageName, it.className, it.timestamp
                )

                UsageEvents.Event.FOREGROUND_SERVICE_START -> PackageLifecycleEvent(
                    State.STARTING, it.packageName, it.className, it.timestamp
                )

                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> PackageLifecycleEvent(
                    State.STOPPING, it.packageName, it.className, it.timestamp
                )

                else -> null
            }
        }
}
