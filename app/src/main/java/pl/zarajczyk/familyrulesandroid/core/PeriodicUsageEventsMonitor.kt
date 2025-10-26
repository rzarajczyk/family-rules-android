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
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Duration

interface SystemEventProcessor {
    fun onMidnight()
    fun onEventBatch(events: List<UsageEvents.Event>, start: Long, end: Long)
}

class PeriodicUsageEventsMonitor(
    private val context: Context,
    private val usageManager: UsageStatsManager,

    private val delayDuration: Duration
) {
    companion object {
        fun install(context: Context, delayDuration: Duration): PeriodicUsageEventsMonitor {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            return PeriodicUsageEventsMonitor(context, manager, delayDuration).also { it.start() }
        }
    }

    private var processors = mutableListOf<SystemEventProcessor>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        // Perform initial calculation immediately
        performTask()
        scope.launch {
            while (isActive) {
                if (ScreenStatus.isScreenOn(context)) {
                    performTask()
                }
                delay(delayDuration)
            }
        }
    }

    private var lastProcessedDay: Instant = Instant.EPOCH
    private var lastProcessedTimestamp: Long = 0L

    fun registerProcessor(processor: SystemEventProcessor) {
        processors.add(processor)
    }


    private fun performTask() {
        val now = Instant.now()
        val startOfDay = now.atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).toInstant()
        val end = now.toEpochMilli()

        if (lastProcessedDay != startOfDay) {
            Log.d("PeriodicUsageEventsMonitor", "New day detected, resetting data")
            lastProcessedTimestamp = 0L
            lastProcessedDay = startOfDay
            processors.forEach {
                it.onMidnight()
            }
        }

        val start = if (lastProcessedTimestamp == 0L) {
            // First run of the day - start from midnight
            startOfDay.toEpochMilli()
        } else {
            // Incremental run - start from last processed timestamp
            lastProcessedTimestamp
        }

        Log.d(
            "PeriodicUsageEventsMonitor",
            "Querying events from $start to $end (incremental: ${lastProcessedTimestamp != 0L})"
        )

        val events = mutableListOf<UsageEvents.Event>()
        // Query the list of events that has happened within that time frame
        val systemEvents = usageManager.queryEvents(start, end)
        while (systemEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            systemEvents.getNextEvent(event)
            events.add(event)
        }

        processors.forEach {
            it.onEventBatch(events, start, end)
        }
    }

}


