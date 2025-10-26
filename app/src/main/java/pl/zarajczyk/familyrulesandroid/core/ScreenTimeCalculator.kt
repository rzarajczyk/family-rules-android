package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents

class ScreenTimeCalculator : SystemEventProcessor {
    companion object {
        fun install(periodicUsageEventsMonitor: PeriodicUsageEventsMonitor): ScreenTimeCalculator {
            val instance = ScreenTimeCalculator()
            periodicUsageEventsMonitor.registerProcessor(instance)
            return instance
        }
    }

    fun getTodayScreenTime(): Long {
        return todayScreenTime
    }

    @Volatile
    private var todayScreenTime: Long = 0L

    enum class ScreenState { TURNING_ON, TURNING_OFF }
    data class ScreenEvent(val state: ScreenState, val timestamp: Long)

    override fun onMidnight() {
        todayScreenTime = 0L
    }

    override fun onEventBatch(events: List<UsageEvents.Event>, start: Long, end: Long) {
        var screenEvents = events.toScreenEvents()

        if (screenEvents.isEmpty()) {
            return
        }

        if (screenEvents.first().state != ScreenState.TURNING_ON) {
            screenEvents = listOf(ScreenEvent(ScreenState.TURNING_ON, start)) + screenEvents
        }
        if (screenEvents.last().state != ScreenState.TURNING_OFF) {
            screenEvents = screenEvents + ScreenEvent(ScreenState.TURNING_OFF, end)
        }
        val batchScreenOnTime = screenEvents
            .chunked(2)
            .sumOf { chunk ->
                chunk.last().timestamp - chunk.first().timestamp
            }

        todayScreenTime += batchScreenOnTime
    }

    private fun List<UsageEvents.Event>.toScreenEvents(): List<ScreenEvent> =
        this.mapNotNull {
            when (it.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> ScreenEvent(
                    ScreenState.TURNING_ON,
                    it.timeStamp
                )

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ScreenEvent(
                    ScreenState.TURNING_OFF,
                    it.timeStamp
                )

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