package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import pl.zarajczyk.familyrulesandroid.core.ScreenTimeCalculator.ScreenEvent
import pl.zarajczyk.familyrulesandroid.core.ScreenTimeCalculator.ScreenState.TURNING_OFF
import pl.zarajczyk.familyrulesandroid.core.ScreenTimeCalculator.ScreenState.TURNING_ON

class EventsRecalculateEnforcer : SystemEventProcessor {
    private lateinit var periodicUsageEventsMonitor: PeriodicUsageEventsMonitor


    override fun reset() {
        // nothing
    }

    override fun processEventBatch(
        events: List<UsageEvents.Event>,
        start: Long,
        end: Long
    ) {
        var screenEvents = events.toScreenEvents()

        if (screenEvents.isEmpty()) {
            return
        }



    }

    fun setMonitor(periodicUsageEventsMonitor: PeriodicUsageEventsMonitor) {
        this.periodicUsageEventsMonitor = periodicUsageEventsMonitor
    }

    private fun List<UsageEvents.Event>.toScreenEvents(): List<ScreenEvent> =
        this.mapNotNull {
            when (it.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> ScreenEvent(TURNING_ON, it.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ScreenEvent(TURNING_OFF, it.timeStamp)
                else -> null
            }
        }.fold(mutableListOf()) { acc, event ->
            if (acc.isEmpty() || acc.last().state != event.state) {
                acc.add(event)
            } else {
                acc[acc.lastIndex] = event
            }
            acc
        }
}