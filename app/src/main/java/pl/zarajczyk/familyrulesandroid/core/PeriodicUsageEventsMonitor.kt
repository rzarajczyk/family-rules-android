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
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface SystemEventProcessor {
    fun reset()
    fun processEventBatch(events: List<UsageEvents.Event>, start: Long, end: Long)
}

class PeriodicUsageEventsMonitor(
    private val context: Context,
    private val usageManager: UsageStatsManager,
    private val delayDuration: Duration,
    private val processors: List<SystemEventProcessor>
) {
    companion object {
        fun install(context: Context, delayDuration: Duration, processors: List<SystemEventProcessor>): PeriodicUsageEventsMonitor {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            return PeriodicUsageEventsMonitor(context, manager, delayDuration, processors).also { it.start() }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        scope.launch {
            delay(2.seconds)
            performTask()
            delay(delayDuration)
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

    fun reset() {
        lastProcessedTimestamp  = 0L
        processors.forEach {
            it.reset()
        }
    }


    private fun performTask() {
        val now = Instant.now()
        val startOfDay = now.atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).toInstant()
        val end = now.toEpochMilli()

        if (lastProcessedDay != startOfDay) {
            Log.d("PeriodicUsageEventsMonitor", "New day detected, resetting data")
            lastProcessedDay = startOfDay
            reset()
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

        if (events.isNotEmpty()) {
            lastProcessedTimestamp = events.last().timeStamp
        }

        processors.forEach {
            it.processEventBatch(events, start, end)
        }

        Log.d("PeriodicUsageEventsMonitor", "Processed ${events.size} events in ${System.currentTimeMillis() - now.toEpochMilli()}ms")
    }

}


